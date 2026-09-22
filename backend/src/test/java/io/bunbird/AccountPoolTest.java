package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

class AccountPoolTest {
    @TempDir Path temp;
    JdbcTemplate db;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void init() throws Exception {
        db=new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa",""));
        db.execute("CREATE TABLE task(id VARCHAR PRIMARY KEY,tenant_id VARCHAR,account_id VARCHAR)");
        db.execute("CREATE TABLE run_event(task_id VARCHAR,type VARCHAR,detail VARCHAR,created_at BIGINT)");
        db.execute("CREATE TABLE account_state(id VARCHAR PRIMARY KEY,reason VARCHAR,retry_at BIGINT DEFAULT 0,disabled BOOLEAN DEFAULT FALSE,failures INT DEFAULT 0,updated_at BIGINT)");
    }
    AccountPool pool(boolean backupKey) throws Exception {
        var file=temp.resolve("accounts.json");
        Files.writeString(file,"""
            [{"id":"primary","name":"Main","mode":"anthropic","model":"model","baseUrl":"https://provider.test","keyEnv":"FIRST_KEY"},
             {"id":"backup","name":"Backup","mode":"anthropic","model":"model","baseUrl":"https://provider.test","keyEnv":"SECOND_KEY"}]
            """);
        var env=new MockEnvironment().withProperty("BUNBIRD_ACCOUNTS_FILE",file.toString())
            .withProperty("FIRST_KEY","secret-first").withProperty("AGENT_EXECUTOR","claude");
        if(backupKey) env.withProperty("SECOND_KEY","secret-second");
        return new AccountPool(env,json,db,new QuotaService(()->null,java.time.Clock.systemUTC(),false));
    }
    @Test void selectsPriorityPersistsCooldownAndRecovers() throws Exception {
        var pool=pool(true);
        assertThat(pool.select(List.of())).containsEntry("id","primary");
        pool.failure("primary","ACCOUNT_RATE_LIMIT");
        assertThat(pool.select(List.of())).containsEntry("id","backup");
        assertThat(pool(true).select(List.of())).containsEntry("id","backup");
        pool.failure("backup","ACCOUNT_QUOTA");
        assertThat(pool.admission().state()).isEqualTo("BLOCK");
        assertThat(pool.admission().resumeAt()).isNotNull();
        assertThat(pool.select(List.of())).isEmpty();
        db.update("UPDATE account_state SET retry_at=0 WHERE id='primary'");
        assertThat(pool.select(List.of())).containsEntry("id","primary");
        pool.change("primary","disable");
        assertThat(pool.select(List.of())).isEmpty();
        pool.change("primary","enable");
        pool.failure("primary","ACCOUNT_AUTH");
        db.update("UPDATE account_state SET retry_at=0");
        assertThat(pool.select(List.of())).containsEntry("id","backup");
        pool.change("primary","reset");
        assertThat(pool.select(List.of())).containsEntry("id","primary");
        assertThat(pool.snapshot().toString()).doesNotContain("secret-first","secret-second","FIRST_KEY","SECOND_KEY");
    }
    @Test void missingKeyAndAttemptedAccountsAreNotSelected() throws Exception {
        var pool=pool(false);
        assertThat(pool.select(List.of("primary"))).isEmpty();
        pool.failure("primary","ACCOUNT_AUTH");
        assertThat(pool.admission().state()).isEqualTo("BLOCK");
    }
    @Test void freshExhaustionBlocksAndUnknownDoesNotPretendRecovery() {
        var quota=org.mockito.Mockito.mock(QuotaService.class);
        var env=new MockEnvironment().withProperty("MODEL_MODE","anthropic")
            .withProperty("MODEL_NAME","model").withProperty("MODEL_API_KEY","secret");
        var pool=new AccountPool(env,json,db,quota);
        org.mockito.Mockito.when(quota.get()).thenReturn(Map.of("status","AVAILABLE","windows",
            List.of(Map.of("model","general","remainingPercent",0,"resetsAt",System.currentTimeMillis()+100000))));
        assertThat(pool.select(List.of())).isEmpty();
        assertThat(pool.admission().state()).isEqualTo("BLOCK");
        org.mockito.Mockito.when(quota.get()).thenReturn(Map.of("status","STALE"));
        assertThat(pool.admission().state()).isEqualTo("UNKNOWN");
        org.mockito.Mockito.when(quota.get()).thenReturn(Map.of("status","AVAILABLE","windows",
            List.of(Map.of("model","general","remainingPercent",25))));
        assertThat(pool.admission().state()).isEqualTo("ALLOW");
    }
}
