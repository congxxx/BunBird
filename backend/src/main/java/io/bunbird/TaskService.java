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
    static final int MAX_CONCURRENT = 3;

    private void lockScheduler() { db.queryForList("SELECT id FROM scheduler_guard WHERE id=1 FOR UPDATE"); }
    private int activeCount() {
        return db.queryForObject("SELECT COUNT(*) FROM task WHERE tenant_id=? AND (status='RUNNING' OR (status='CANCELLED' AND lease_until>?))",Integer.class,tenant,now());
    }
    private void notifyCapacityQueue() {
        if(activeCount()<MAX_CONCURRENT) return;
        for(var task:db.queryForList("SELECT id,message_id FROM task WHERE tenant_id=? AND status='QUEUED' ORDER BY position",tenant)) {
            String taskId=(String)task.get("id");
            if(db.queryForObject("SELECT COUNT(*) FROM run_event WHERE task_id=? AND type='CAPACITY_QUEUE'",Integer.class,taskId)>0) continue;
            event(taskId,"CAPACITY_QUEUE","并发会话已满，等待执行空位");
            enqueue(taskId,(String)task.get("message_id"),"⏳ 会话排队中…（最多 3 个并发）");
            db.update("UPDATE delivery SET task_state='CAPACITY_QUEUE' WHERE task_id=? AND id=(SELECT id FROM delivery WHERE task_id=? ORDER BY position DESC LIMIT 1)",taskId,taskId);
        }
    }

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
    void requireIdentity(Incoming in) {
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
        return receive(in,List.of());
    }
    @Transactional
    public Map<String, Object> receive(Incoming in,List<AccountPool.ModelOption> models) {
        requireIdentity(in);
        lockScheduler();
        String ingressId = id();
        db.update("INSERT INTO ingress(id,tenant_id,app_id,event_id,message_id,created_at,chat_type) VALUES(?,?,?,?,?,?,?)",
                ingressId, tenant, appId, in.eventId(), in.messageId(), now(), in.chatType());
        var topics = db.queryForList("SELECT id FROM topic WHERE tenant_id=? AND app_id=? AND chat_id=? AND external_key=?",
                tenant, appId, in.chatId(), in.topicKey());
        String topicId = topics.isEmpty() ? id() : (String) topics.getFirst().get("id");
        if (topics.isEmpty()) db.update("INSERT INTO topic(id,tenant_id,app_id,chat_id,external_key) VALUES(?,?,?,?,?)",
                topicId, tenant, appId, in.chatId(), in.topicKey());
        db.queryForList("SELECT id FROM topic WHERE id=? FOR UPDATE", topicId);
        String attachmentScope=in.chatType().equals("group") && in.topicKey().equals(in.messageId())?"chat":in.topicKey();
        if(in.attachment()==null && db.queryForObject("SELECT COUNT(*) FROM attachment WHERE tenant_id=? AND app_id=? AND chat_id=? AND sender_id=? AND message_id=? AND scope_key='chat' AND task_id IS NULL",Integer.class,tenant,appId,in.chatId(),in.senderId(),in.topicKey())>0) attachmentScope="chat";
        if(in.attachment()!=null) {
            var file=in.attachment();
            int count=db.queryForObject("SELECT COUNT(*) FROM attachment WHERE tenant_id=? AND app_id=? AND chat_id=? AND sender_id=? AND scope_key=? AND task_id IS NULL",Integer.class,tenant,appId,in.chatId(),in.senderId(),attachmentScope);
            if(count>=10) enqueue(null,in.messageId(),"待处理附件已达 10 个，请先发送文字处理已有文件，再上传新文件。");
            else {
                db.update("INSERT INTO attachment(id,tenant_id,app_id,chat_id,sender_id,scope_key,message_id,file_name,file_size,sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    file.id(),tenant,appId,in.chatId(),in.senderId(),attachmentScope,in.messageId(),file.name(),file.size(),file.sha256(),now());
                enqueue(null,in.messageId(),"📎 已收到文件「"+file.name()+"」（共 "+(count+1)+" 个待处理）。发送文字说明后将一并交给 AI。");
            }
            return Map.of("accepted",true,"attachment",true);
        }
        String text = in.text().strip();
        String[] words = text.split("\\s+", 2);
        String command = words[0].toLowerCase(Locale.ROOT);
        if (command.startsWith("/") && !command.equals("/workspace")) {
            if (command.equals("/model")) {
                modelCommand(in,topicId,words.length==2?words[1].strip():"",models);
            } else if (command.equals("/attachment-too-large")) {
                enqueue(null,in.messageId(),"附件超过 10 MB，未保存。请缩小文件后重新发送。");
            } else if (command.equals("/attachment-download-failed")) {
                enqueue(null,in.messageId(),"附件下载失败，未保存。请确认应用具有消息资源下载权限后重新发送文件。");
            } else if (command.equals("/help")) {
                enqueue(null, in.messageId(), "BunBird 已连接。直接发送文字即可提问；群聊无需 @机器人，每条新消息都会创建任务。\n/model 查看可用模型\n/model 模型ID 切换当前会话模型\n/model auto 恢复账号池自动选择\n/status [任务ID] 查看状态\n/cancel [任务ID] 取消自己的任务\n/help 查看帮助\n启用 Claude Code 时可直接发送拉取项目、修改代码等任务要求；在原话题继续回复即可续接会话。上传文件后发送文字说明，可将附件一并交给 Agent。");
            } else if (command.equals("/unsupported")) {
                enqueue(null, in.messageId(), "当前支持文字和文件消息。上传文件后，请发送文字说明处理要求。");
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
        // The topic lock makes replacement atomic with receive/claim. Retain the old
        // lease until the worker acknowledges process cleanup (or its lease expires).
        for (var old : db.queryForList("SELECT id,message_id FROM task WHERE tenant_id=? AND topic_id=? AND sender_id=? AND status IN ('QUEUED','RUNNING','WAITING_QUOTA')", tenant, topicId, in.senderId())) {
            String oldId = (String) old.get("id");
            if (db.update("UPDATE task SET status='CANCELLED',pause_reason=NULL,resume_at=NULL,error_code='SUPERSEDED',updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_QUOTA')", now(), oldId) == 0) continue;
            event(oldId, "SUPERSEDED", "已被同一会话的新消息中断");
            // Drop unsent stale progress, but let an in-flight delivery finish before closing its card.
            db.update("UPDATE delivery SET status='FAILED',error_code='TASK_SUPERSEDED' WHERE task_id=? AND status='PENDING'", oldId);
            enqueue(oldId, (String) old.get("message_id"), "");
            db.update("UPDATE delivery SET task_state='SUPERSEDED' WHERE task_id=? AND status='PENDING'", oldId);
        }
        // Bound the queue for the small, single-tenant pilot.
        Integer waiting = db.queryForObject("SELECT COUNT(*) FROM task WHERE tenant_id=? AND status IN ('QUEUED','RUNNING','WAITING_QUOTA')", Integer.class, tenant);
        if (waiting != null && waiting >= 100) {
            enqueue(null, in.messageId(), "当前排队任务较多，请稍后重试。");
            return Map.of("accepted", true, "busy", true);
        }
        String taskId = id();
        db.update("INSERT INTO task(id,tenant_id,topic_id,sender_id,message_id,prompt,status,created_at,updated_at) VALUES(?,?,?,?,?,?,'QUEUED',?,?)",
                taskId, tenant, topicId, in.senderId(), in.messageId(), text, now(), now());
        var preference=db.queryForList("SELECT account_id,model FROM session_model WHERE topic_id=? AND sender_id=?",topicId,in.senderId());
        if(!preference.isEmpty())db.update("UPDATE task SET requested_account_id=?,requested_model=? WHERE id=?",preference.getFirst().get("account_id"),preference.getFirst().get("model"),taskId);
        db.update("UPDATE ingress SET task_id=? WHERE id=?", taskId, ingressId);
        db.update("UPDATE attachment SET task_id=? WHERE tenant_id=? AND app_id=? AND chat_id=? AND sender_id=? AND scope_key=? AND task_id IS NULL",
            taskId,tenant,appId,in.chatId(),in.senderId(),attachmentScope);
        event(taskId, "QUEUED", "已受理，等待执行");
        // A durable reaction-only delivery: no acknowledgement text is sent to the chat.
        enqueue(taskId, in.messageId(), "");
        notifyCapacityQueue();
        return Map.of("accepted", true, "taskId", taskId);
    }

    public List<Map<String, Object>> tasks() {
        return db.queryForList("SELECT id,topic_id,sender_id,status,model,account_id,error_code,pause_reason,resume_at,created_at,updated_at FROM task WHERE tenant_id=? ORDER BY position DESC LIMIT 100", tenant);
    }
    public Map<String, Object> task(String taskId) {
        var rows = db.queryForList("SELECT id,tenant_id,topic_id,sender_id,message_id,prompt,status,result,error_code,model,account_id,requested_account_id,requested_model,usage_json,pause_reason,resume_at,created_at,updated_at FROM task WHERE id=? AND tenant_id=?", taskId, tenant);
        if (rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        return rows.getFirst();
    }
    private void modelCommand(Incoming in,String topicId,String argument,List<AccountPool.ModelOption> models) {
        if(argument.equalsIgnoreCase("auto")) {
            db.update("DELETE FROM session_model WHERE topic_id=? AND sender_id=?",topicId,in.senderId());
            enqueue(null,in.messageId(),"已恢复账号池自动选择，从你在当前话题的下一条任务消息生效。上下文和工作区保留，当前任务不受影响。");return;
        }
        if(argument.isEmpty()) {
            var preference=db.queryForList("SELECT account_id,model FROM session_model WHERE topic_id=? AND sender_id=?",topicId,in.senderId());
            String current=preference.isEmpty()?"账号池自动选择":preference.getFirst().get("model").toString();
            if(!preference.isEmpty()) {
                String selectedAccount=(String)preference.getFirst().get("account_id");
                current=models.stream().filter(m->m.accountId().equals(selectedAccount)).map(AccountPool.ModelOption::accountName).findFirst().orElse(selectedAccount)+" / "+current;
            }
            var reply=new StringBuilder("当前会话模型：").append(current).append("\n可选渠道与模型（已配置模型及渠道查询结果，实际可用性以调用为准）：\n");
            for(var model:models) {
                String selector=models.stream().filter(m->m.model().equals(model.model())).count()==1?model.model():model.selector();
                String line="• "+model.accountName()+" · /model "+selector+"\n";
                if(reply.length()+line.length()>9000){reply.append("其余模型请在看板查看。\n");break;}
                reply.append(line);
            }
            if(models.isEmpty())reply.append("暂无已启用且配置完整的账号，请先在看板配置。\n");
            reply.append("发送 /model 模型ID 切换；重名时使用上面的完整命令。/model auto 恢复自动选择。选择仅影响你在当前话题的后续任务。");
            enqueue(null,in.messageId(),reply.toString());return;
        }
        var matches=models.stream().filter(m->m.model().equals(argument)||m.selector().equals(argument)).toList();
        if(matches.size()!=1) {
            enqueue(null,in.messageId(),matches.isEmpty()?"未找到该模型，请发送 /model 查看列表；查询失败的渠道可先在看板配置模型。":"多个渠道提供该模型，请发送 /model 查看并使用包含渠道标识的完整命令。");return;
        }
        var selected=matches.getFirst();
        db.update("DELETE FROM session_model WHERE topic_id=? AND sender_id=?",topicId,in.senderId());
        db.update("INSERT INTO session_model(topic_id,sender_id,account_id,model) VALUES(?,?,?,?)",topicId,in.senderId(),selected.accountId(),selected.model());
        enqueue(null,in.messageId(),"已选择 "+selected.accountName()+" / "+selected.model()+"，从你在当前话题的下一条任务消息生效。上下文和工作区保留，当前任务不受影响。账号不可用时会尝试备用渠道并告知实际模型。");
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
        int changed = db.update("UPDATE task SET status='CANCELLED',pause_reason=NULL,resume_at=NULL,updated_at=? WHERE id=? AND status IN ('QUEUED','RUNNING','WAITING_QUOTA')", now(), taskId);
        if (changed == 1) {
            event(taskId, "CANCELLED", "用户取消");
            enqueue(taskId, (String)row.get("message_id"), "任务 " + taskId + " 已取消。");
        }
        return task(taskId);
    }

    public boolean hasWaitingTasks() {
        return db.queryForObject("SELECT COUNT(*) FROM task WHERE tenant_id=? AND status IN ('QUEUED','WAITING_QUOTA')",Integer.class,tenant)>0;
    }

    @Transactional
    public Map<String,Object> claim() { return claim(QuotaAdmission.allow()); }

    @Transactional
    public Map<String, Object> claim(QuotaAdmission admission) {
        lockScheduler();
        var candidates = db.queryForList("SELECT id,topic_id FROM task WHERE status IN ('QUEUED','WAITING_QUOTA') AND tenant_id=? ORDER BY position LIMIT 100", tenant);
        for (var candidate : candidates) {
            String topicId = (String)candidate.get("topic_id"), taskId = (String)candidate.get("id");
            db.queryForList("SELECT id FROM topic WHERE id=? FOR UPDATE", topicId);
            if (db.queryForObject("SELECT COUNT(*) FROM task WHERE topic_id=? AND (status='RUNNING' OR (status='CANCELLED' AND lease_until>?))", Integer.class, topicId, now()) > 0) continue;
            var current=task(taskId);
            if (!Set.of("QUEUED","WAITING_QUOTA").contains(current.get("status"))) continue;
            if ("BLOCK".equals(admission.state()) || ("UNKNOWN".equals(admission.state()) && "WAITING_QUOTA".equals(current.get("status")))) {
                boolean first=!"WAITING_QUOTA".equals(current.get("status"));
                if(!first && Objects.equals(admission.reason(),current.get("pause_reason"))
                        && Objects.equals(admission.resumeAt(),current.get("resume_at"))) continue;
                if(db.update("UPDATE task SET status='WAITING_QUOTA',pause_reason=?,resume_at=?,updated_at=? WHERE id=? AND status IN ('QUEUED','WAITING_QUOTA')",
                    admission.reason(),admission.resumeAt(),now(),taskId)==0) continue;
                if(first) {
                    db.update("UPDATE delivery SET status='FAILED',error_code='WAITING_QUOTA' WHERE task_id=? AND task_state='CAPACITY_QUEUE' AND status='PENDING'",taskId);
                    String reason="ACCOUNT_POOL_UNAVAILABLE".equals(admission.reason())?"所有账号暂不可用":
                        "QUOTA_WEEKLY_EXHAUSTED".equals(admission.reason())?"周额度已耗尽":"短周期额度已耗尽";
                    String when=admission.resumeAt()==null?"恢复时间暂未确认，将定期检查。":
                        "预计在北京时间 "+java.time.Instant.ofEpochMilli(admission.resumeAt()).atZone(java.time.ZoneId.of("Asia/Shanghai"))
                            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))+" 重置，确认额度恢复后继续。";
                    event(taskId,"WAITING_QUOTA",reason);
                    enqueue(taskId,(String)current.get("message_id"),reason+"，任务尚未开始，已保留会话与工作区。"+when+"等待期间发送新消息会替换当前任务。");
                }
                continue;
            }
            if(activeCount()>=MAX_CONCURRENT) {
                notifyCapacityQueue();
                return Map.of();
            }
            String token = id();
            if (db.update("UPDATE task SET status='RUNNING',pause_reason=NULL,resume_at=NULL,lease_token=?,lease_until=?,deadline=?,updated_at=? WHERE id=? AND status IN ('QUEUED','WAITING_QUOTA')",
                    token, now()+LEASE_MS, now()+DEADLINE_MS, now(), taskId) == 0) continue;
            if("WAITING_QUOTA".equals(current.get("status"))) {
                event(taskId,"QUOTA_RESUMED","已确认额度可用，开始执行");
                db.update("UPDATE delivery SET status='FAILED',error_code='QUOTA_ALREADY_RESUMED' WHERE task_id=? AND task_state='WAITING_QUOTA' AND status='PENDING'",taskId);
            }
            event(taskId, "RUNNING", "Worker 已领取");
            db.update("UPDATE delivery SET status='FAILED',error_code='TASK_ALREADY_STARTED' WHERE task_id=? AND task_state='CAPACITY_QUEUE' AND status='PENDING'",taskId);
            notifyCapacityQueue();
            var task = task(taskId);
            var history = db.queryForList("SELECT prompt,result FROM task WHERE topic_id=? AND status='SUCCEEDED' ORDER BY position DESC LIMIT 6", topicId);
            Collections.reverse(history);
            return Map.of("taskId", taskId, "leaseToken", token, "prompt", task.get("prompt"), "history", history, "tenantId", tenant, "topicId", topicId, "senderId", task.get("sender_id"), "attachments",
                db.queryForList("SELECT id,file_name AS name,file_size AS size,sha256 FROM attachment WHERE task_id=? AND tenant_id=? ORDER BY created_at,id",taskId,tenant),
                "accountRetry",current.get("account_id")!=null);
        }
        return Map.of();
    }

    @Transactional
    public Map<String, Object> stopped(String taskId, String token) {
        db.update("UPDATE task SET lease_token=NULL,lease_until=NULL WHERE id=? AND tenant_id=? AND lease_token=? AND status='CANCELLED'", taskId, tenant, token);
        return Map.of("accepted", true);
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
        enqueue(taskId, (String)task(taskId).get("message_id"), result.status().equals("SUCCEEDED") ? body : body + "\n\n任务 " + taskId);
        return Map.of("accepted", true);
    }

    private Map<String,Object> leasedTask(String taskId,String token) {
        var rows=db.queryForList("SELECT * FROM task WHERE id=? AND tenant_id=? FOR UPDATE",taskId,tenant);
        if(rows.isEmpty()) throw new ResponseStatusException(NOT_FOUND);
        var row=rows.getFirst();
        if(!"RUNNING".equals(row.get("status")) || !token.equals(row.get("lease_token"))
            || ((Number)row.get("lease_until")).longValue()<=now()
            || ((Number)row.get("deadline")).longValue()<=now()) throw new ResponseStatusException(CONFLICT,"Lease lost");
        return row;
    }

    @Transactional
    public void accountFailure(String taskId,String token,String failed,String reason,AccountPool pool) {
        var row=leasedTask(taskId,token);
        if(failed!=null) {
            if(!failed.equals(row.get("account_id"))) throw new ResponseStatusException(CONFLICT);
            pool.failure(failed,reason);
            event(taskId,"ACCOUNT_UNAVAILABLE",failed+": "+reason);
        }
    }

    @Transactional
    public Map<String,Object> assignAccount(String taskId,String token,Map<String,Object> account,QuotaAdmission admission) {
        var row=leasedTask(taskId,token);
        if(account.isEmpty()) {
            db.update("UPDATE task SET status='WAITING_QUOTA',pause_reason='ACCOUNT_POOL_UNAVAILABLE',resume_at=?,lease_token=NULL,lease_until=NULL,updated_at=? WHERE id=?",
                admission.resumeAt(),now(),taskId);
            event(taskId,"WAITING_QUOTA","所有账号暂不可用，保留会话与工作区");
            enqueue(taskId,(String)row.get("message_id"),"可用账号暂时不足，已保留会话与工作区，稍后自动检查并继续。");
            return Map.of("waiting",true);
        }
        String next=(String)account.get("id");
        if(!next.equals(row.get("account_id"))) {
            String previous=Objects.toString(row.get("account_id"),"");
            db.update("UPDATE task SET account_id=?,updated_at=? WHERE id=?",next,now(),taskId);
            event(taskId,previous.isEmpty()?"ACCOUNT_SELECTED":"ACCOUNT_SWITCHED",previous.isEmpty()?next:previous+" → "+next);
            if(!previous.isEmpty() || (row.get("requested_account_id")!=null && !next.equals(row.get("requested_account_id"))))
                enqueue(taskId,(String)row.get("message_id"),"原选账号暂不可用，本轮已切换至 "+account.get("name")+" / "+account.get("model")+"。会话上下文和工作区继续保留。");
        }
        var selected=new LinkedHashMap<>(account);
        int offset=0;
        for(var type:db.queryForList("SELECT type FROM run_event WHERE task_id=? AND type LIKE 'PROGRESS_UPDATE_%'",String.class,taskId)) {
            try { offset=Math.max(offset,Integer.parseInt(type.substring("PROGRESS_UPDATE_".length()))); }
            catch(NumberFormatException ignored) {}
        }
        selected.put("progressOffset",offset);
        return selected;
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
                return Map.of("deliveryId", deliveryId, "leaseToken", token, "replyTo", row.get("reply_to"), "content", row.get("content"), "replyInThread", row.get("reply_in_thread"), "taskState", Objects.toString(row.get("task_state"), ""), "taskId", Objects.toString(row.get("task_id"), ""));
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
