package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QuotaServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void usesExplicitRemainingPercentEvenWhenCountersAreZeroAndCaches() throws Exception {
        var body=json.readTree("""
            {"base_resp":{"status_code":0},"model_remains":[{"model_name":"general",
            "current_interval_total_count":0,"current_interval_usage_count":0,
            "current_interval_remaining_percent":100,"current_weekly_remaining_percent":"97",
            "start_time":1789905600000,"end_time":1789920000000}]}
            """);
        var calls=new AtomicInteger();
        var service=new QuotaService(()->{calls.incrementAndGet();return body;},Clock.systemUTC(),true);
        var result=service.get();
        assertThat(result).containsEntry("status","AVAILABLE");
        @SuppressWarnings("unchecked") var windows=(List<Map<String,Object>>)result.get("windows");
        assertThat(windows).hasSize(2);
        assertThat(windows.getFirst()).containsEntry("remainingPercent",100.0).containsEntry("usedPercent",0.0)
            .containsEntry("resetsAt",1789920000000L);
        assertThat(windows.getLast()).containsEntry("remainingPercent",97.0);
        service.get();assertThat(calls.get()).isEqualTo(1);
    }
    @Test void rejectsMissingOrInvalidQuotaAndNeverReturnsProviderError() throws Exception {
        for(String response:List.of("{}","{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"sensitive\"}}",
                "{\"base_resp\":{\"status_code\":0},\"model_remains\":[{\"current_interval_remaining_percent\":-1}]}")) {
            var body=json.readTree(response);
            var result=new QuotaService(()->body,Clock.systemUTC(),true).get();
            assertThat(result).containsEntry("status","UNAVAILABLE");
            assertThat(result.toString()).doesNotContain("sensitive");
        }
        var unsupported=new QuotaService(()->{throw new AssertionError("must not call provider");},Clock.systemUTC(),false);
        assertThat(unsupported.get()).containsEntry("status","UNSUPPORTED");
    }
    @Test void cachedSuccessIsExplicitlyStaleWhenRefreshFails() throws Exception {
        var time=new AtomicLong(100000);
        Clock clock=new Clock() {
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return Instant.ofEpochMilli(time.get());}
        };
        var body=json.readTree("{\"base_resp\":{\"status_code\":0},\"model_remains\":[{\"current_interval_remaining_percent\":20}]}");
        var fail=new AtomicBoolean();
        var service=new QuotaService(()->{if(fail.get())throw new IllegalStateException("secret");return body;},clock,true);
        assertThat(service.get()).containsEntry("status","AVAILABLE");
        time.addAndGet(61000);fail.set(true);
        assertThat(service.get()).containsEntry("status","STALE").containsEntry("checkedAt",100000L);
    }
}
