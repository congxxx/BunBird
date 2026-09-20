package io.bunbird;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class QuotaAdmissionTest {
    Map<String,Object> window(String model,String kind,double remaining,long reset) {
        return Map.of("model",model,"kind",kind,"remainingPercent",remaining,"resetsAt",reset);
    }
    @Test void bothWindowsMustRecoverAndVideoDoesNotBlockCoding() {
        var data=Map.<String,Object>of("status","AVAILABLE","windows",List.of(
            window("general","interval",0,1000),window("general","weekly",0,5000),window("video","interval",0,9000)));
        var decision=QuotaAdmission.from(data,"MiniMax-M3",100);
        assertThat(decision.state()).isEqualTo("BLOCK");assertThat(decision.resumeAt()).isEqualTo(5000);
        assertThat(decision.reason()).isEqualTo("QUOTA_WEEKLY_EXHAUSTED");
        var available=Map.<String,Object>of("status","AVAILABLE","windows",List.of(
            window("general","interval",10,1000),window("general","weekly",20,5000),window("video","interval",0,9000)));
        assertThat(QuotaAdmission.from(available,"MiniMax-M3",100).state()).isEqualTo("ALLOW");
    }
    @Test void unknownAndExpiredResetDoNotInventRecovery() {
        for(String status:List.of("STALE","UNAVAILABLE")) {
            assertThat(QuotaAdmission.from(Map.of("status",status),"MiniMax-M3",100).state()).isEqualTo("UNKNOWN");
        }
        var data=Map.<String,Object>of("status","AVAILABLE","windows",List.of(window("general","interval",0,50)));
        var decision=QuotaAdmission.from(data,"MiniMax-M3",100);
        assertThat(decision.state()).isEqualTo("BLOCK");assertThat(decision.resumeAt()).isNull();
    }
}
