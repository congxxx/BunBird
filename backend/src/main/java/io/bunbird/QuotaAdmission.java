package io.bunbird;

import java.util.*;

/** A read-only quota decision. UNKNOWN never invents exhaustion or authorizes a paused retry. */
public record QuotaAdmission(String state, String reason, Long resumeAt) {
    static QuotaAdmission allow() { return new QuotaAdmission("ALLOW",null,null); }
    static QuotaAdmission unknown() { return new QuotaAdmission("UNKNOWN","QUOTA_UNCONFIRMED",null); }

    static QuotaAdmission from(Map<String,Object> quota,String model,long now) {
        if("UNSUPPORTED".equals(quota.get("status"))) return allow();
        if(!"AVAILABLE".equals(quota.get("status"))) return unknown();
        var relevant=new ArrayList<Map<?,?>>();
        if(quota.get("windows") instanceof List<?> windows) {
            for(Object value:windows) if(value instanceof Map<?,?> w
                    && ("general".equals(w.get("model")) || model.equals(w.get("model")))) relevant.add(w);
        }
        if(relevant.isEmpty()) return unknown();
        boolean blocked=false,weekly=false,unknownReset=false;
        long reset=0;
        for(var window:relevant) {
            if(!(window.get("remainingPercent") instanceof Number percentage)) return unknown();
            if(percentage.doubleValue()<=0) {
                blocked=true; weekly|="weekly".equals(window.get("kind"));
                if(window.get("resetsAt") instanceof Number n && n.longValue()>now) reset=Math.max(reset,n.longValue());
                else unknownReset=true;
            }
        }
        return blocked?new QuotaAdmission("BLOCK",weekly?"QUOTA_WEEKLY_EXHAUSTED":"QUOTA_INTERVAL_EXHAUSTED",
            unknownReset?null:reset):allow();
    }
}
