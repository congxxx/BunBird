package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static io.bunbird.Protocol.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "bunbird.tenant=tenant-test", "bunbird.app-id=cli-test",
    "bunbird.bridge-token=bridge-012345678901234567890123456789",
    "bunbird.worker-token=worker-012345678901234567890123456789",
    "bunbird.admin-token=admin-012345678901234567890123456789"
})
@AutoConfigureMockMvc
class TaskServiceTest {
    static final String BRIDGE="Bearer bridge-012345678901234567890123456789";
    static final String WORKER="Bearer worker-012345678901234567890123456789";
    @Autowired TaskService service;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @org.springframework.test.context.bean.override.mockito.MockitoBean QuotaService quota;

    @BeforeEach void clear() {
        org.mockito.Mockito.when(quota.get()).thenReturn(Map.of("status","UNSUPPORTED"));
        for (String table : List.of("delivery","run_event","task","ingress","topic")) db.update("DELETE FROM " + table);
    }
    Incoming message(String event, String user, String topic, String text) {
        return new Incoming("tenant-test","cli-test",event,"om-"+event,"chat-test",user,topic,text,"group");
    }
    String submit(String event, String user, String topic, String text) {
        return (String) service.receive(message(event,user,topic,text)).get("taskId");
    }
    Completion success(Map<String,Object> job) {
        return new Completion((String)job.get("leaseToken"),"SUCCEEDED","你好",null,"test-model","{}");
    }

    @Test void repeatedEventsAndDifferentEventIdsForSameMessageCreateOneTask() throws Exception {
        var incoming=message("event","user","topic","你好");
        for (int i=0;i<10;i++) mvc.perform(post("/internal/bridge/events").header("Authorization",BRIDGE)
                .contentType("application/json").content(json.writeValueAsBytes(incoming))).andExpect(status().isOk());
        var sameMessage=new Incoming("tenant-test","cli-test","other-event","om-event","chat-test","user","topic","你好","group");
        mvc.perform(post("/internal/bridge/events").header("Authorization",BRIDGE).contentType("application/json")
                .content(json.writeValueAsBytes(sameMessage))).andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        assertThat(service.tasks()).hasSize(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM delivery",Integer.class)).isEqualTo(1);
    }
    @Test void authorizationSeparatesCapabilitiesAndTenant() throws Exception {
        mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tasks").header("Authorization",WORKER)).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/worker/claim").header("Authorization",BRIDGE)).andExpect(status().isUnauthorized());
        var other=new Incoming("other-tenant","cli-test","e","m","c","u","t","hello","group");
        mvc.perform(post("/internal/bridge/events").header("Authorization",BRIDGE).contentType("application/json")
                .content(json.writeValueAsBytes(other))).andExpect(status().isForbidden());
        assertThat(service.tasks()).isEmpty();
    }
    @Test void validationRejectsMalformedAndOversizedEvents() throws Exception {
        mvc.perform(post("/internal/bridge/events").header("Authorization",BRIDGE).contentType("application/json")
                .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/bridge/events").header("Authorization",BRIDGE).contentType("application/json")
                .content(json.writeValueAsBytes(message("long","u","t","x".repeat(12001)))))
                .andExpect(status().isBadRequest());
    }
    @Test void completeAndNotifyAreIdempotentAndIndependent() {
        String id=submit("e","u","t","问题");
        var job=service.claim();
        service.complete(id,success(job));
        service.complete(id,success(job));
        assertThat(service.events(id,0)).extracting(e->e.get("type")).containsExactly("QUEUED","RUNNING","SUCCEEDED");
        assertThat(service.deliveries(id)).hasSize(2);
        assertThat(db.queryForObject("SELECT content FROM delivery WHERE task_id=? AND task_state='SUCCEEDED'",String.class,id)).isEqualTo("你好");
        var delivery=service.claimDelivery();
        service.delivered((String)delivery.get("deliveryId"),new Delivered((String)delivery.get("leaseToken"),false,null,"FEISHU_429"));
        assertThat(service.task(id).get("status")).isEqualTo("SUCCEEDED");
        assertThat(service.claim()).isEmpty();
    }
    @Test void cancellationFencesLateWorkerResults() {
        String id=submit("e","u","t","问题");
        var job=service.claim();
        service.cancel(id);
        assertThat(service.heartbeat(id,(String)job.get("leaseToken"))).containsEntry("active",false);
        assertThatThrownBy(()->service.complete(id,success(job))).hasMessageContaining("409");
        assertThat(service.task(id)).containsEntry("status","CANCELLED");
        assertThat(service.deliveries(id)).hasSize(2);
    }

    @Test void workspaceCommandAndProgressAreDurableAndLeaseFenced() {
        String id=submit("workspace","u","t","/workspace bunbird");
        assertThat(id).isNotNull();
        var job=service.claim();
        assertThat(job).containsEntry("tenantId", "tenant-test");
        var progress=new Progress((String)job.get("leaseToken"), "STARTED", "Session: " + id);
        service.progress(id,progress);
        service.progress(id,progress);
        assertThat(service.deliveries(id)).hasSize(2);
        assertThat(db.queryForObject("SELECT task_state FROM delivery WHERE task_id=? ORDER BY position DESC LIMIT 1", String.class,id)).isEqualTo("STARTED");
        service.cancel(id);
        assertThatThrownBy(()->service.progress(id,new Progress(progress.leaseToken(),"WORKSPACE","preparing"))).hasMessageContaining("409");
        assertThat(service.deliveries(id)).hasSize(3);
    }

    @Test void queuedDeliveryIsSilentAndProgressRevisionsAreIdempotent() {
        String id=submit("progress","u","t","hello");
        var queued=service.claimDelivery();
        assertThat(queued).containsEntry("taskState","QUEUED").containsEntry("content", "").containsEntry("taskId",id);
        var job=service.claim();
        String token=(String)job.get("leaseToken");
        service.progress(id,new Progress(token,"UPDATE_0","analyzing"));
        service.progress(id,new Progress(token,"UPDATE_1","analyzing\nreading"));
        service.progress(id,new Progress(token,"UPDATE_1","analyzing\nreading"));
        assertThat(service.deliveries(id)).hasSize(3);
        service.complete(id,success(job));
        assertThatThrownBy(()->service.progress(id,new Progress(token,"UPDATE_2","late"))).hasMessageContaining("409");
    }
    @Test void queuedCancellationCannotBeClaimed() {
        String id=submit("e","u","t","问题");
        service.cancel(id);
        assertThat(service.claim()).isEmpty();
    }
    @Test void anotherUserOrChatCannotCancelByGuessingTaskId() {
        String id=submit("e","owner","t","问题");
        service.receive(message("cancel-other","attacker","t","/cancel "+id));
        service.receive(new Incoming("tenant-test","cli-test","other-chat","om-other","other-chat","owner","t","/cancel "+id,"group"));
        assertThat(service.task(id)).containsEntry("status","QUEUED");
        service.receive(message("cancel-owner","owner","t","/cancel "+id));
        assertThat(service.task(id)).containsEntry("status","CANCELLED");
    }
    @Test void sameTopicSerializesAndHistoryDoesNotCrossTopics() {
        String first=submit("e1","u","t","第一问");
        var job=service.claim();
        submit("e2","other-user","t","第二问");
        assertThat(service.claim()).isEmpty();
        String other=submit("e3","u","other-topic","另外的话题");
        var separate=service.claim();
        assertThat(separate.get("taskId")).isEqualTo(other);
        assertThat((List<?>)separate.get("history")).isEmpty();
        service.complete(first,success(job));
        var followup=service.claim();
        assertThat((List<?>)followup.get("history")).hasSize(1);
    }
    @Test void newestMessageFencesOldRunAndWaitsForProcessCleanup() throws Exception {
        String first=submit("first","u","t","old");
        var job=service.claim();
        String token=(String)job.get("leaseToken");
        service.progress(first,new Progress(token,"UPDATE_0","old progress"));
        String second=submit("second","u","t","new");
        String latest=submit("latest","u","t","newest");
        assertThat(service.task(first)).containsEntry("status","CANCELLED").containsEntry("error_code","SUPERSEDED");
        assertThat(service.task(second)).containsEntry("status","CANCELLED");
        assertThat(service.heartbeat(first,token)).containsEntry("active",false);
        assertThatThrownBy(()->service.complete(first,success(job))).hasMessageContaining("409");
        assertThatThrownBy(()->service.progress(first,new Progress(token,"UPDATE_1","late"))).hasMessageContaining("409");
        assertThat(service.claim()).isEmpty();
        service.stopped(first,"wrong-token");
        assertThat(service.claim()).isEmpty();
        // A redelivered old event must not supersede the latest message.
        mvc.perform(post("/internal/bridge/events").header("Authorization",BRIDGE)
            .contentType("application/json").content(json.writeValueAsBytes(message("first","u","t","old"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(true));
        service.stopped(first,token);
        assertThat(service.claim()).containsEntry("taskId",latest);
        assertThat(service.claimDelivery()).containsEntry("taskState","SUPERSEDED").containsEntry("content","");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM delivery WHERE task_id=? AND task_state='UPDATE_0' AND status='PENDING'",Integer.class,first)).isZero();
    }
    @Test void supersessionDoesNotCrossUsersTopicsOrCommandsAndRecoversLostWorker() {
        String first=submit("first","u","t","old");
        service.claim();
        submit("status","u","t","/status");
        submit("other-user","v","t","other");
        submit("other-topic","u","separate","other");
        assertThat(service.task(first)).containsEntry("status","RUNNING");
        submit("new","u","t","replacement");
        db.update("UPDATE task SET lease_until=0 WHERE id=?",first);
        assertThat(service.claim()).isNotEmpty();
    }
    @Test void simultaneousWorkersCannotClaimSameTopic() throws Exception {
        submit("e1","u","t","第一问"); submit("e2","u","t","第二问");
        try (var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Map<String,Object>> claim=()->{start.await();return service.claim();};
            var a=pool.submit(claim);var b=pool.submit(claim);start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS)).stream().filter(r->!r.isEmpty()).count()).isEqualTo(1);
        }
    }
    @Test void expiredLeaseFailsWithoutReexecution() {
        String id=submit("e","u","t","问题");var job=service.claim();
        db.update("UPDATE task SET lease_until=0 WHERE id=?",id);
        assertThat(service.heartbeat(id,(String)job.get("leaseToken"))).containsEntry("active",false);
        service.expire();service.expire();
        assertThat(service.task(id)).containsEntry("status","FAILED").containsEntry("error_code","WORKER_LOST_OR_TIMEOUT");
        assertThat(service.claim()).isEmpty();assertThat(service.deliveries(id)).hasSize(2);
        assertThatThrownBy(()->service.complete(id,success(job))).hasMessageContaining("409");
    }
    @Test void expiredDeadlineRejectsHeartbeatAndCompletion() {
        String id=submit("e","u","t","问题");var job=service.claim();
        db.update("UPDATE task SET deadline=0 WHERE id=?",id);
        assertThat(service.heartbeat(id,(String)job.get("leaseToken"))).containsEntry("active",false);
        assertThatThrownBy(()->service.complete(id,success(job))).hasMessageContaining("409");
    }
    @Test void deliveryRetryKeepsIdAndStopsAfterFiveFailures() {
        String id=submit("e","u","t","问题");
        String deliveryId=null;
        for(int i=0;i<5;i++) {
            db.update("UPDATE delivery SET available_at=0");
            var delivery=service.claimDelivery();
            if(deliveryId==null) deliveryId=(String)delivery.get("deliveryId");
            assertThat(delivery.get("deliveryId")).isEqualTo(deliveryId);
            service.delivered(deliveryId,new Delivered((String)delivery.get("leaseToken"),false,null,"NETWORK"));
        }
        assertThat(service.claimDelivery()).isEmpty();
        assertThat(service.deliveries(id).getFirst()).containsEntry("status","FAILED").containsEntry("attempts",5);
    }
    @Test void finalDeliveryCrashDoesNotRemainSendingForever() {
        String id=submit("e","u","t","问题");
        db.update("UPDATE delivery SET status='SENDING',attempts=5,available_at=0");
        assertThat(service.claimDelivery()).isEmpty();
        assertThat(service.deliveries(id).getFirst()).containsEntry("status","FAILED");
    }
    @Test void staleDeliveryLeaseCannotAcknowledgeNewAttempt() {
        submit("e","u","t","问题");var first=service.claimDelivery();
        db.update("UPDATE delivery SET available_at=0");var second=service.claimDelivery();
        assertThat(second.get("deliveryId")).isEqualTo(first.get("deliveryId"));
        assertThatThrownBy(()->service.delivered((String)first.get("deliveryId"),
                new Delivered((String)first.get("leaseToken"),true,"om-result",null))).hasMessageContaining("409");
    }
    @Test void helpAndUnsupportedMessagesDoNotCallModel() {
        submit("e1","u","t","/help");submit("e2","u","t","/unsupported");
        assertThat(service.tasks()).isEmpty();assertThat(service.claim()).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM delivery",Integer.class)).isEqualTo(2);
    }

    @Test void groupAcknowledgementAndResultBothCreateThreadReplies() {
        String id=submit("thread","u","root","开始一个话题");
        var accepted=service.claimDelivery();
        assertThat(accepted).containsEntry("replyInThread",true).containsEntry("replyTo","om-thread");
        service.delivered((String)accepted.get("deliveryId"),new Delivered((String)accepted.get("leaseToken"),true,"om-ack",null));
        var job=service.claim();service.complete(id,success(job));
        assertThat(service.claimDelivery()).containsEntry("replyInThread",true).containsEntry("replyTo","om-thread");
    }

    @Test void directMessagesKeepNormalRepliesAndGroupCommandsUseThreads() {
        service.receive(new Incoming("tenant-test","cli-test","dm","om-dm","chat-test","u","dm:u","你好","p2p"));
        var direct=service.claimDelivery();
        assertThat(direct).containsEntry("replyInThread",false);
        service.delivered((String)direct.get("deliveryId"),new Delivered((String)direct.get("leaseToken"),true,"om-dm-ack",null));
        service.receive(message("help","u","root","/help"));
        assertThat(service.claimDelivery()).containsEntry("replyInThread",true);
    }

    @Test void notificationSnapshotsPreserveStagesAndOrder() {
        String id=submit("stages","u","root","问题");
        var job=service.claim();service.complete(id,success(job));
        var accepted=service.claimDelivery();
        assertThat(accepted).containsEntry("taskState","QUEUED");
        assertThat(service.claimDelivery()).isEmpty();
        service.delivered((String)accepted.get("deliveryId"),new Delivered((String)accepted.get("leaseToken"),true,"om-ack",null));
        assertThat(service.claimDelivery()).containsEntry("taskState","SUCCEEDED");
    }

    @Test void dashboardSeparatesCacheUsageAndDoesNotExposeTaskContent() throws Exception {
        String a=submit("usage-a","u","a","private prompt sentinel");
        String b=submit("usage-b","u","b","private prompt sentinel");
        submit("usage-unknown","u","unknown","private prompt sentinel");
        db.update("UPDATE task SET status='SUCCEEDED',model='anthropic-test',result='private result sentinel',usage_json=? WHERE id=?",
            "{\"input_tokens\":100,\"output_tokens\":20,\"cache_read_input_tokens\":30,\"cache_creation_input_tokens\":40}",a);
        db.update("UPDATE task SET status='SUCCEEDED',model='compatible-test',usage_json=? WHERE id=?",
            "{\"prompt_tokens\":100,\"completion_tokens\":10,\"prompt_tokens_details\":{\"cached_tokens\":50}}",b);
        var result=mvc.perform(get("/api/dashboard?days=7").header("Authorization","Bearer admin-012345678901234567890123456789"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tasks").value(3))
            .andExpect(jsonPath("$.reportedTasks").value(2)).andExpect(jsonPath("$.usage.total").value(300))
            .andExpect(jsonPath("$.usage.cacheRead").value(80)).andExpect(jsonPath("$.daily.length()").value(7))
            .andExpect(jsonPath("$.accounts[0].quotaStatus").value("UNKNOWN"))
            .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("private prompt sentinel","private result sentinel","usage_json","lease_token");
    }

    @Test void dashboardRequiresAdminButStaticLoginPageIsPublic() throws Exception {
        mvc.perform(get("/dashboard.html")).andExpect(status().isOk());
        mvc.perform(get("/dashboard.js")).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/quota")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/quota").header("Authorization",WORKER)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard").header("Authorization",WORKER)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard?days=-1").header("Authorization","Bearer admin-012345678901234567890123456789"))
            .andExpect(status().isBadRequest());
        String other=submit("other-tenant","u","t","private");
        db.update("UPDATE task SET tenant_id='different' WHERE id=?",other);
        mvc.perform(get("/api/dashboard").header("Authorization","Bearer admin-012345678901234567890123456789"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.tasks").value(0));
    }

    @Test void quotaWaitIsDurableDeduplicatedAndResumesOnlyAfterPositiveCheck() {
        String id=submit("quota","u","t","hello");
        long reset=System.currentTimeMillis()+3600000;
        var blocked=new QuotaAdmission("BLOCK","QUOTA_WEEKLY_EXHAUSTED",reset);
        assertThat(service.claim(blocked)).isEmpty();
        assertThat(service.claim(blocked)).isEmpty();
        assertThat(service.task(id)).containsEntry("status","WAITING_QUOTA").containsEntry("resume_at",reset);
        assertThat(service.deliveries(id)).hasSize(2);
        service.expire();
        assertThat(service.task(id)).containsEntry("status","WAITING_QUOTA");
        assertThat(service.claim(QuotaAdmission.unknown())).isEmpty();
        assertThat(service.task(id)).containsEntry("pause_reason","QUOTA_UNCONFIRMED");
        assertThat(service.deliveries(id)).hasSize(2);
        var job=service.claim(QuotaAdmission.allow());
        assertThat(job).containsEntry("taskId",id);
        service.complete(id,success(job));
        assertThat(service.claim(blocked)).isEmpty();
        assertThat(service.events(id,0)).extracting(e->e.get("type")).containsExactly("QUEUED","WAITING_QUOTA","QUOTA_RESUMED","RUNNING","SUCCEEDED");
    }

    @Test void newMessagesAndCancelReplaceQuotaWaitAndUnknownDoesNotMeanExhausted() {
        String old=submit("old-quota","u","t","old");
        var blocked=new QuotaAdmission("BLOCK","QUOTA_INTERVAL_EXHAUSTED",null);
        service.claim(blocked);
        String latest=submit("latest-quota","u","t","new");
        assertThat(service.task(old)).containsEntry("status","CANCELLED");
        service.claim(blocked);
        service.cancel(latest);
        assertThat(service.claim(QuotaAdmission.allow())).isEmpty();
        String fresh=submit("unknown-quota","u","t","fresh");
        assertThat(service.claim(QuotaAdmission.unknown())).containsEntry("taskId",fresh);
        assertThat(service.claim(blocked)).isEmpty();
        assertThat(service.task(fresh)).containsEntry("status","RUNNING");
    }

    @Test void workerClaimEndpointAppliesQuotaBeforeExecution() throws Exception {
        String id=submit("quota-endpoint","u","t","hello");
        org.mockito.Mockito.when(quota.get()).thenReturn(Map.of("status","AVAILABLE","windows",List.of(
            Map.of("model","general","kind","interval","remainingPercent",0,"resetsAt",System.currentTimeMillis()+3600000))));
        mvc.perform(post("/internal/worker/claim").header("Authorization",WORKER))
            .andExpect(status().isOk()).andExpect(content().json("{}"));
        assertThat(service.task(id)).containsEntry("status","WAITING_QUOTA");
        org.mockito.Mockito.when(quota.get()).thenReturn(Map.of("status","AVAILABLE","windows",List.of(
            Map.of("model","general","kind","interval","remainingPercent",100))));
        mvc.perform(post("/internal/worker/claim").header("Authorization",WORKER))
            .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").value(id));
    }
}
