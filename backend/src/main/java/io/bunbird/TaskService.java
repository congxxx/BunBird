package io.bunbird;

import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import static io.bunbird.Protocol.*;

/** The database is the authority for tasks and the transactional notification outbox. */
@Service
public class TaskService {
    private final JdbcTemplate db;
    private final String tenant, appId;
    private final Clock clock;
    static final long LEASE_MS = 30_000, DEADLINE_MS = 900_000;

    @org.springframework.beans.factory.annotation.Autowired
    public TaskService(JdbcTemplate db, @Value("${bunbird.tenant}") String tenant,
                       @Value("${bunbird.app-id}") String appId) {
        this(db, tenant, appId, Clock.systemUTC());
    }
    TaskService(JdbcTemplate db, String tenant, String appId, Clock clock) {
        this.db = db; this.tenant = tenant; this.appId = appId; this.clock = clock;
    }
    private long now() { return clock.millis(); }
    private String id() { return UUID.randomUUID().toString(); }
    private void requireIdentity(Incoming in) {
        if (!tenant.equals(in.tenantId()) || !appId.equals(in.appId()))
            throw new ResponseStatusException(FORBIDDEN, "Application or tenant not allowed");
    }

    public Map<String, Object> duplicate(Incoming in) {
        requireIdentity(in);
        var rows = db.queryForList("SELECT task_id FROM ingress WHERE tenant_id=? AND app_id=? AND (event_id=? OR message_id=?)",
                tenant, appId, in.eventId(), in.messageId());
        if (rows.isEmpty()) throw new ResponseStatusException(CONFLICT, "Retry event");
        var answer = new HashMap<String, Object>();
        answer.put("duplicate", true); answer.put("taskId", rows.getFirst().get("task_id"));
        return answer;
    }

    @Transactional
    public Map<String, Object> receive(Incoming in) {
        requireIdentity(in);
        String ingressId = id();
        db.update("INSERT INTO ingress(id,tenant_id,app_id,event_id,message_id,created_at,chat_type) VALUES(?,?,?,?,?,?,?)",
                ingressId, tenant, appId, in.eventId(), in.messageId(), now(), in.chatType());
        var topics = db.queryForList("SELECT id FROM topic WHERE tenant_id=? AND app_id=? AND chat_id=? AND external_key=?",
                tenant, appId, in.chatId(), in.topicKey());
        String topicId = topics.isEmpty() ? id() : (String) topics.getFirst().get("id");
        if (topics.isEmpty()) db.update("INSERT INTO topic(id,tenant_id,app_id,chat_id,external_key) VALUES(?,?,?,?,?)",
                topicId, tenant, appId, in.chatId(), in.topicKey());
        db.queryForList("SELECT id FROM topic WHERE id=? FOR UPDATE", topicId);
        String text = in.text().strip();
        String[] words = text.split("\\s+", 2);
        String command = words[0].toLowerCase(Locale.ROOT);
        if (command.startsWith("/") && !command.equals("/workspace")) {
            if (command.equals("/help")) {
                enqueue(null, in.messageId(), "BunBird 已连接。直接发送文字即可提问；群聊无需 @机器人，每条新消息都会创建任务。\n/status [任务ID] 查看状态\n/cancel [任务ID] 取消自己的任务\n/help 查看帮助\n启用 Claude Code 时可直接发送拉取项目、修改代码等任务要求；在原话题继续回复即可续接会话。");
            } else if (command.equals("/unsupported")) {
                enqueue(null, in.messageId(), "当前最小版仅支持文字消息，请将问题以文字发送。");
            } else if (command.equals("/too-long")) {
                enqueue(null, in.messageId(), "消息超过 12000 字符，请缩短后发送。");
            } else if (command.equals("/status") || command.equals("/cancel")) {
                // Explicit IDs still require the originating user, tenant and chat.
                var rows = words.length == 2
                        ? db.queryForList("SELECT t.* FROM task t JOIN topic p ON p.id=t.topic_id WHERE t.id=? AND t.tenant_id=? AND t.sender_id=? AND p.chat_id=?",
                            words[1].strip(), tenant, in.senderId(), in.chatId())
                        : db.queryForList("SELECT * FROM task WHERE topic_id=? AND sender_id=? ORDER BY position DESC LIMIT 1", topicId, in.senderId());
                if (rows.isEmpty()) enqueue(null, in.messageId(), "未找到你在当前会话中的任务。可附上完整任务 ID。");
                else {
                    String taskId = (String) rows.getFirst().get("id");
                    if (command.equals("/cancel")) cancel(taskId);
                    enqueue(null, in.messageId(), "任务 " + taskId + "\n状态：" + task(taskId).get("status"));
                }
            } else enqueue(null, in.messageId(), "未知命令，发送 /help 查看帮助。");
            return Map.of("accepted", true, "command", true);
        }
        // Bound the queue for the small, single-tenant pilot.
        Integer waiting = db.queryForObject("SELECT COUNT(*) FROM task WHERE tenant_id=? AND status IN ('QUEUED','RUNNING')", Integer.class, tenant);
        if (waiting != null && waiting >= 100) {
            enqueue(null, in.messageId(), "当前排队任务较多，请稍后重试。");
            return Map.of("accepted", true, "busy", true);
        }
        String taskId = id();
        db.update("INSERT INTO task(id,tenant_id,topic_id,sender_id,message_id,prompt,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'QUEUED',?,?)",
                taskId, tenant, topicId, in.senderId(), in.messageId(), text, now(), now());
        db.update("UPDATE ingress SET task_id=? WHERE id=?", taskId, ingressId);
        event(taskId, "QUEUED", "已受理，等待执行");
        enqueue(taskId, in.messageId(), "已受理，任务 " + taskId + "。\n可发送 /status " + taskId + " 或 /cancel " + taskId + "。");
        return Map.of("accepted", true, "taskId", taskId);
    }

    public List<Map<String, Object>> tasks() {
        return db.queryForList("SELECT id,topic_id,sender_id,status,model,error_code,created_at,updated_at FROM task WHERE tenant_id=? ORDER BY position DESC LIMIT 100", tenant);
    }
    public Map<String, Object> task(String taskId) {
        var rows = db.queryForList("SELECT id,tenant_id,topic_id,sender_id,message_id,prompt,status,result,error_code,model,usage_json,created_at,updated_at FROM task WHERE id=? AND tenant_id=?", taskId, tenant);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        return rows.getFirst();
    }
    public List<Map<String, Object>> events(String taskId, long after) {
        task(taskId);
        return db.queryForList("SELECT sequence,type,detail,created_at FROM run_event WHERE task_id=? AND sequence>? ORDER BY sequence LIMIT 200", taskId, after);
    }
    public List<Map<String, Object>> deliveries(String taskId) {
        task(taskId);
        return db.queryForList("SELECT id,status,attempts,error_code,platform_message_id FROM delivery WHERE task_id=? ORDER BY position", taskId);
    }

    @Transactional
    public Map<String, Object> cancel(String taskId) {
        var row = task(taskId);
        int changed = db.update("UPDATE task SET status='CANCELLED',lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING')", now(), taskId);
        if (changed == 1) {
            event(taskId, "CANCELLED", "用户取消");
            enqueue(taskId, (String)row.get("message_id"), "任务 " + taskId + " 已取消。");
        }
        return task(taskId);
    }

    @Transactional
    public Map<String, Object> claim() {
        var candidates = db.queryForList("SELECT id,topic_id FROM task WHERE status='QUEUED' AND tenant_id=? ORDER BY position LIMIT 100", tenant);
        for (var candidate : candidates) {
            String topicId = (String)candidate.get("topic_id"), taskId = (String)candidate.get("id");
            db.queryForList("SELECT id FROM topic WHERE id=? FOR UPDATE", topicId);
            if (db.queryForObject("SELECT COUNT(*) FROM task WHERE topic_id=? AND status='RUNNING'", Integer.class, topicId) > 0) continue;
            String token = id();
            if (db.update("UPDATE task SET status='RUNNING',lease_token=?,lease_until=?,deadline=?,updated_at=? WHERE id=? AND status='QUEUED'",
                    token, now()+LEASE_MS, now()+DEADLINE_MS, now(), taskId) == 0) continue;
            event(taskId, "RUNNING", "Worker 已领取");
            var task = task(taskId);
            var history = db.queryForList("SELECT prompt,result FROM task WHERE topic_id=? AND status='SUCCEEDED' ORDER BY position DESC LIMIT 6", topicId);
            Collections.reverse(history);
            return Map.of("taskId", taskId, "leaseToken", token, "prompt", task.get("prompt"), "history", history, "tenantId", tenant, "topicId", topicId, "senderId", task.get("sender_id"));
        }
        return Map.of();
    }

    @Transactional
    public Map<String, Object> heartbeat(String taskId, String token) {
        int changed = db.update("UPDATE task SET lease_until=? WHERE id=? AND tenant_id=? AND lease_token=? AND status='RUNNING' AND lease_until>? AND deadline>?",
                now()+LEASE_MS, taskId, tenant, token, now(), now());
        return Map.of("active", changed == 1);
    }

    @Transactional
    public Map<String, Object> progress(String taskId, Progress progress) {
        var rows = db.queryForList("SELECT * FROM task WHERE id=? AND tenant_id=? FOR UPDATE", taskId, tenant);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        var row = rows.getFirst();
        if (!"RUNNING".equals(row.get("status")) || !progress.leaseToken().equals(row.get("lease_token"))
                || ((Number)row.get("lease_until")).longValue() <= now()
                || ((Number)row.get("deadline")).longValue() <= now())
            throw new ResponseStatusException(CONFLICT, "Lease lost");
        String type = "PROGRESS_" + progress.phase();
        if (db.queryForObject("SELECT COUNT(*) FROM run_event WHERE task_id=? AND type=?", Integer.class, taskId, type) == 0) {
            event(taskId, type, progress.detail());
            enqueue(taskId, (String)row.get("message_id"), progress.detail());
            db.update("UPDATE delivery SET task_state=? WHERE task_id=? AND id=(SELECT id FROM delivery WHERE task_id=? ORDER BY position DESC LIMIT 1)", progress.phase(), taskId, taskId);
        }
        return Map.of("accepted", true);
    }

    @Transactional
    public Map<String, Object> complete(String taskId, Completion result) {
        if (result.status().equals("SUCCEEDED") && (result.result() == null || result.result().isBlank()))
            throw new ResponseStatusException(BAD_REQUEST, "Empty result");
        int changed = db.update("UPDATE task SET status=?,result=?,error_code=?,model=?,usage_json=?,lease_until=NULL,updated_at=? WHERE id=? AND tenant_id=? AND lease_token=? AND status='RUNNING' AND lease_until>? AND deadline>?",
                result.status(), result.result(), result.errorCode(), result.model(), result.usageJson(), now(), taskId, tenant, result.leaseToken(), now(), now());
        if (changed == 0) {
            // A lost response to an already committed completion must not create a new delivery.
            var rows = db.queryForList("SELECT status,lease_token FROM task WHERE id=? AND tenant_id=?", taskId, tenant);
            if (!rows.isEmpty() && result.leaseToken().equals(rows.getFirst().get("lease_token"))
                    && result.status().equals(rows.getFirst().get("status"))) return Map.of("accepted", true);
            throw new ResponseStatusException(CONFLICT, "Lease lost or task cancelled");
        }
        event(taskId, result.status(), result.status().equals("SUCCEEDED") ? "模型回答完成" : Objects.toString(result.errorCode(), "MODEL_FAILED"));
        String body = result.status().equals("SUCCEEDED") ? result.result()
                : "任务执行失败（" + Objects.toString(result.errorCode(), "MODEL_FAILED") + "），可稍后重新发送问题。";
        enqueue(taskId, (String)task(taskId).get("message_id"), body + "\n\n任务 " + taskId);
        return Map.of("accepted", true);
    }

    @Transactional
    public void expire() {
        for (var row : db.queryForList("SELECT id,message_id FROM task WHERE status='RUNNING' AND (lease_until<? OR deadline<?)", now(), now())) {
            String taskId = (String)row.get("id");
            if (db.update("UPDATE task SET status='FAILED',error_code='WORKER_LOST_OR_TIMEOUT',lease_token=NULL,updated_at=? WHERE id=? AND status='RUNNING' AND (lease_until<? OR deadline<?)", now(), taskId, now(), now()) == 1) {
                event(taskId, "FAILED", "执行租约或总时限到期；不自动重复调用模型");
                enqueue(taskId, (String)row.get("message_id"), "任务 " + taskId + " 因执行超时或 Worker 失联而失败，请重新发送问题。");
            }
        }
    }

    @Transactional
    public Map<String, Object> claimDelivery() {
        // Crashes consume an attempt too. Do not leave the final crashed attempt stuck forever.
        db.update("UPDATE delivery SET status='FAILED',error_code='DELIVERY_ATTEMPTS_EXHAUSTED' WHERE status='SENDING' AND available_at<=? AND attempts>=5", now());
        var rows = db.queryForList("SELECT d.* FROM delivery d WHERE d.status IN ('PENDING','SENDING') AND d.available_at<=? AND d.attempts<5 AND NOT EXISTS (SELECT 1 FROM delivery p WHERE p.task_id=d.task_id AND p.position<d.position AND p.status IN ('PENDING','SENDING')) ORDER BY d.position LIMIT 20", now());
        for (var row : rows) {
            String token = id(), deliveryId = (String)row.get("id");
            if (db.update("UPDATE delivery SET status='SENDING',lease_token=?,available_at=?,attempts=attempts+1 WHERE id=? AND status IN ('PENDING','SENDING') AND available_at<=? AND attempts<5",
                    token, now()+60_000, deliveryId, now()) == 1)
                return Map.of("deliveryId", deliveryId, "leaseToken", token, "replyTo", row.get("reply_to"), "content", row.get("content"), "replyInThread", row.get("reply_in_thread"), "taskState", Objects.toString(row.get("task_state"), ""));
        }
        return Map.of();
    }

    @Transactional
    public void delivered(String deliveryId, Delivered answer) {
        var rows = db.queryForList("SELECT attempts FROM delivery WHERE id=? AND lease_token=? AND status='SENDING' FOR UPDATE", deliveryId, answer.leaseToken());
        if (rows.isEmpty()) throw new ResponseStatusException(CONFLICT);
        int attempts = ((Number)rows.getFirst().get("attempts")).intValue();
        String status = answer.success() ? "SENT" : attempts >= 5 ? "FAILED" : "PENDING";
        db.update("UPDATE delivery SET status=?,error_code=?,platform_message_id=?,available_at=? WHERE id=? AND lease_token=?",
                status, answer.errorCode(), answer.messageId(), now() + Math.min(30_000, 1000L << attempts), deliveryId, answer.leaseToken());
    }

    private void event(String taskId, String type, String detail) {
        db.update("INSERT INTO run_event(task_id,type,detail,created_at) VALUES(?,?,?,?)", taskId, type, detail, now());
    }
    private void enqueue(String taskId, String replyTo, String content) {
        // Fixed UUID is reused by the connector as the Feishu reply idempotency key.
        String chatType = db.queryForObject("SELECT chat_type FROM ingress WHERE tenant_id=? AND app_id=? AND message_id=?",
                String.class, tenant, appId, replyTo);
        String taskState = taskId == null ? null : (String) task(taskId).get("status");
        db.update("INSERT INTO delivery(id,task_id,reply_to,content,status,available_at,created_at,reply_in_thread,task_state) VALUES(?,?,?,?,'PENDING',?,?,?,?)",
                id(), taskId, replyTo, content, now(), now(), "group".equals(chatType), taskState);
    }
}
