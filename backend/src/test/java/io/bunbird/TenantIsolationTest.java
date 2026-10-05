package io.bunbird;

import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:tenants;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "bunbird.tenant=enterprise-a","bunbird.app-id=cli_a",
    "bunbird.bridge-token=bridge-012345678901234567890123456789",
    "bunbird.worker-token=worker-012345678901234567890123456789",
    "bunbird.admin-token=admin-012345678901234567890123456789",
    "MODEL_MODE=mock","MODEL_NAME=mock-demo","AGENT_EXECUTOR=claude",
    "BUNBIRD_ACCOUNT_MASTER_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@AutoConfigureMockMvc
class TenantIsolationTest {
    static final String ADMIN="admin-012345678901234567890123456789",BRIDGE="bridge-012345678901234567890123456789",WORKER="worker-012345678901234567890123456789";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired IdentityService identities;
    @Autowired AccountPool accounts;
    @Autowired SkillHub skills;
    @TempDir Path temp;
    String root,bridgeB,memberB;
    @BeforeEach void setup() throws Exception {
        for(String table:List.of("feishu_identity","invitation","login_session","membership","app_user","managed_skill","session_model","managed_account","account_state","attachment","delivery","run_event","task","ingress","topic","organization"))db.update("DELETE FROM "+table);
        identities.migrate();root=postJson("/api/auth/bootstrap",ADMIN,null,Map.of("username","owner","password","safe-test-password"),200).path("token").asText();
        postJson("/api/organizations",root,null,Map.of("name","B","id","enterprise-b"),200);
        bridgeB=postJson("/api/organization/feishu",root,"enterprise-b",Map.of("appId","cli_b"),200).path("bridgeToken").asText();
        String invite=postJson("/api/organization/invitations",root,"enterprise-b",Map.of("role","MEMBER"),200).path("code").asText();
        memberB=postJson("/api/auth/register",null,null,Map.of("username","member","password","member-test-password","code",invite),200).path("token").asText();
    }
    JsonNode postJson(String url,String token,String tenant,Object body,int status) throws Exception {
        var request=post(url).with(r->{r.setRemoteAddr(UUID.randomUUID().toString());return r;}).contentType("application/json").content(json.writeValueAsBytes(body));
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(tenant!=null)request.header("X-Tenant-ID",tenant);
        return json.readTree(mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }
    JsonNode getJson(String url,String token,String tenant,int expected) throws Exception {
        var request=get(url).header("Authorization","Bearer "+token);if(tenant!=null)request.header("X-Tenant-ID",tenant);
        String body=mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return body.isBlank()?json.createObjectNode():json.readTree(body);
    }
    Object message(String tenant,String app,String event,String text) {
        return Map.of("tenantId",tenant,"appId",app,"eventId",event,"messageId","om-"+event,"chatId","chat","senderId","same-user","topicKey","topic","text",text,"chatType","group");
    }
    @Test void membershipsRolesInvitationsAndSessionsAreEnforced() throws Exception {
        getJson("/api/dashboard",memberB,"enterprise-a",403);
        getJson("/api/dashboard",memberB,"enterprise-b",200);
        postJson("/api/accounts",memberB,"enterprise-b",Map.of(),403);
        postJson("/api/organizations",memberB,"enterprise-b",Map.of("name","illegal"),403);
        postJson("/api/auth/bootstrap",memberB,"enterprise-b",Map.of("username","bad","password","safe-test-password"),403);
        String invite=postJson("/api/organization/invitations",root,"enterprise-a",Map.of("role","MEMBER"),200).path("code").asText();
        postJson("/api/auth/accept",memberB,"enterprise-b",Map.of("code",invite),200);
        getJson("/api/dashboard",memberB,"enterprise-a",200);
        postJson("/api/auth/accept",memberB,"enterprise-b",Map.of("code",invite),400);
        String memberId=(String)db.queryForMap("SELECT id FROM app_user WHERE username='member'").get("id");
        mvc.perform(delete("/api/organization/members/"+memberId).header("Authorization","Bearer "+root).header("X-Tenant-ID","enterprise-a")).andExpect(status().isOk());
        getJson("/api/dashboard",memberB,"enterprise-a",403);
        postJson("/api/auth/logout",memberB,"enterprise-b",Map.of(),200);
        getJson("/api/dashboard",memberB,"enterprise-b",401);
        assertThat(db.queryForObject("SELECT password_hash FROM app_user WHERE username='owner'",String.class)).doesNotContain("safe-test-password");
    }
    @Test void tasksOutboxAndBridgeIdentitiesCannotCrossTenants() throws Exception {
        String a=postJson("/internal/bridge/events",BRIDGE,null,message("enterprise-a","cli_a","a","hello A"),200).path("taskId").asText();
        String b=postJson("/internal/bridge/events",bridgeB,null,message("enterprise-b","cli_b","b","hello B"),200).path("taskId").asText();
        postJson("/internal/bridge/events",BRIDGE,null,message("enterprise-b","cli_b","bad","spoof"),403);
        postJson("/internal/bridge/events",bridgeB,null,message("enterprise-b","cli_a","bad","spoof"),403);
        getJson("/api/tasks/"+a,memberB,"enterprise-b",404);
        getJson("/api/tasks/"+a+"/events",memberB,"enterprise-b",404);
        postJson("/api/tasks/"+a+"/cancel",root,"enterprise-b",Map.of(),404);
        assertThat(getJson("/api/tasks",root,"enterprise-a",200).size()).isEqualTo(1);
        assertThat(getJson("/api/tasks",memberB,"enterprise-b",200).get(0).path("id").asText()).isEqualTo(b);
        postJson("/internal/bridge/events",BRIDGE,null,message("enterprise-a","cli_a","ca","/help"),200);
        postJson("/internal/bridge/events",bridgeB,null,message("enterprise-b","cli_b","cb","/help"),200);
        var delivery=postJson("/internal/bridge/deliveries/claim",bridgeB,null,Map.of(),200);
        assertThat(delivery.path("replyTo").asText()).isIn("om-b","om-cb");
        postJson("/internal/bridge/deliveries/"+delivery.path("deliveryId").asText()+"/ack",BRIDGE,null,Map.of("leaseToken",delivery.path("leaseToken").asText(),"success",true,"messageId","sent"),409);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM feishu_identity",Integer.class)).isEqualTo(2);
    }
    @Test void catalogsCredentialsSkillsAndWorkerSelectionAreTenantScoped() throws Exception {
        var edit=Map.of("name","B channel","mode","anthropic","model","test-model","baseUrl","https://provider.test","apiKey","private-B-key","priority",1);
        String account=postJson("/api/accounts",root,"enterprise-b",edit,200).path("id").asText();
        assertThat(getJson("/api/accounts",root,"enterprise-a",200).toString()).doesNotContain("B channel","private-B-key");
        var bAccounts=getJson("/api/accounts",memberB,"enterprise-b",200);
        assertThat(bAccounts.path("accounts").size()).isEqualTo(1);
        assertThat(bAccounts.toString()).doesNotContain("environment","private-B-key");
        postJson("/api/accounts/"+account+"/disable",root,"enterprise-a",Map.of(),400);
        postJson("/api/accounts/models",root,"enterprise-a",Map.of("accountId",account,"baseUrl","https://provider.test","mode","anthropic"),400);
        Files.writeString(temp.resolve("SKILL.md"),"# Tenant B skill");
        String skill=postJson("/api/skills/import",root,"enterprise-b",Map.of("type","local","source",temp.toString(),"enabled",true),200).path("id").asText();
        getJson("/api/skills/"+skill,root,"enterprise-a",404);
        postJson("/api/skills/"+skill+"/state",root,"enterprise-a",Map.of("enabled",false),404);
        String task=postJson("/internal/bridge/events",bridgeB,null,message("enterprise-b","cli_b","work","do it"),200).path("taskId").asText();
        var claim=postJson("/internal/worker/claim",WORKER,null,Map.of(),200);
        assertThat(claim.path("tenantId").asText()).isEqualTo("enterprise-b");
        assertThat(claim.path("taskId").asText()).isEqualTo(task);
        var selected=postJson("/internal/worker/tasks/"+task+"/account",WORKER,null,Map.of("leaseToken",claim.path("leaseToken").asText(),"excluded",List.of()),200);
        assertThat(selected.path("id").asText()).isEqualTo(account);
        assertThat(selected.path("skills").get(0).path("id").asText()).isEqualTo(skill);
        postJson("/internal/worker/tasks/"+task+"/account",WORKER,null,Map.of("leaseToken","wrong","excluded",List.of()),409);
        // Thread-local identity must have been cleared after every HTTP request.
        assertThat(TenantContext.current()).isNull();
        assertThat(accounts.select(List.of()).get("id")).isEqualTo("environment");
    }
    @Test void migrationPreservesExistingUnownedCatalogAndDeliveryRows() {
        db.update("INSERT INTO managed_skill(id,name,source_type,source,revision,enabled,files_json,instructions,created_at) VALUES('old','old','local','x','revision',TRUE,'{}','instructions',1)");
        db.update("INSERT INTO delivery(id,reply_to,content,status,available_at,created_at) VALUES('old','message','content','PENDING',1,1)");
        identities.migrate();identities.migrate();
        assertThat(db.queryForObject("SELECT tenant_id FROM managed_skill WHERE id='old'",String.class)).isEqualTo("enterprise-a");
        assertThat(db.queryForObject("SELECT tenant_id FROM delivery WHERE id='old'",String.class)).isEqualTo("enterprise-a");
        assertThat(db.queryForObject("SELECT instructions FROM managed_skill WHERE id='old'",String.class)).isEqualTo("instructions");
    }
    @Test void existingUserCanRejoinAfterRemovalButMustProvePassword() throws Exception {
        String user=db.queryForObject("SELECT id FROM app_user WHERE username='member'",String.class);
        db.update("DELETE FROM membership WHERE user_id=?",user);
        String invite=postJson("/api/organization/invitations",root,"enterprise-b",Map.of("role","MEMBER"),200).path("code").asText();
        postJson("/api/auth/register",null,null,Map.of("username","member","password","wrong-password","code",invite),401);
        var login=postJson("/api/auth/register",null,null,Map.of("username","member","password","member-test-password","code",invite),200);
        getJson("/api/dashboard",login.path("token").asText(),"enterprise-b",200);
        postJson("/api/auth/login",null,null,Map.of("username","member","password","member-test-password"),200);
        db.update("UPDATE login_session SET expires_at=0 WHERE user_id=?",user);
        getJson("/api/dashboard",login.path("token").asText(),"enterprise-b",401);
        getJson("/api/dashboard",ADMIN,"enterprise-b",403);
        postJson("/api/auth/bootstrap",ADMIN,null,Map.of("username","second","password","safe-test-password"),409);
    }
}
