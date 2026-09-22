package io.bunbird;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** Read-only, admin-authenticated usage view. Never returns prompts, results or credentials. */
@RestController
public class DashboardApi {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String tenant, model, executor;

    public DashboardApi(JdbcTemplate db, ObjectMapper json,
            @Value("${bunbird.tenant}") String tenant,
            @Value("${MODEL_NAME:未配置}") String model,
            @Value("${AGENT_EXECUTOR:model}") String executor) {
        this.db=db; this.json=json; this.tenant=tenant; this.model=model; this.executor=executor;
    }

    @GetMapping("/api/dashboard")
    public Object dashboard(@RequestParam(defaultValue="30") int days) {
        if (!Set.of(7,30,90).contains(days)) throw new ResponseStatusException(BAD_REQUEST);
        var zone=ZoneId.of("Asia/Shanghai");
        var today=LocalDate.now(zone);
        long since=today.minusDays(days-1).atStartOfDay(zone).toInstant().toEpochMilli();
        var rows=db.queryForList("SELECT id,status,model,account_id,usage_json,error_code,pause_reason,resume_at,created_at,updated_at FROM task WHERE tenant_id=? AND created_at>=? ORDER BY position DESC",tenant,since);
        var daily=new LinkedHashMap<String,long[]>();
        for (int i=days-1;i>=0;i--) daily.put(today.minusDays(i).toString(),new long[5]);
        var statuses=new TreeMap<String,Integer>();
        var models=new TreeMap<String,long[]>();
        var recent=new ArrayList<Map<String,Object>>();
        long[] total=new long[5];
        int reported=0;
        for (var row:rows) {
            statuses.merge((String)row.get("status"),1,Integer::sum);
            String modelName=Objects.toString(row.get("model"),"未记录模型");
            long[] usage=parseUsage(Objects.toString(row.get("usage_json"),""));
            String day=Instant.ofEpochMilli(((Number)row.get("created_at")).longValue()).atZone(zone).toLocalDate().toString();
            if (usage!=null) {
                reported++;
                add(total,usage);
                add(daily.computeIfAbsent(day,k->new long[5]),usage);
                add(models.computeIfAbsent(modelName,k->new long[5]),usage);
            }
            if (recent.size()<100) {
                var item=new LinkedHashMap<String,Object>();
                for(String key:List.of("id","status","model","account_id","error_code","pause_reason","resume_at","created_at","updated_at")) item.put(key,row.get(key));
                item.put("usage",usage==null?null:metrics(usage));
                recent.add(item);
            }
        }
        var trend=new ArrayList<Map<String,Object>>();
        daily.forEach((day,value)->trend.add(Map.of("date",day,"usage",metrics(value))));
        var byModel=new ArrayList<Map<String,Object>>();
        models.forEach((name,value)->byModel.add(Map.of("model",name,"usage",metrics(value))));
        return Map.of("days",days,"tasks",rows.size(),"reportedTasks",reported,"statuses",statuses,
                "usage",metrics(total),"daily",trend,"models",byModel,"recent",recent,
                "accounts",List.of(Map.of("id","environment","name","当前环境配置","model",model,
                    "executor",executor,"status","CONFIGURED","quotaStatus","UNKNOWN")),
                "generatedAt",System.currentTimeMillis());
    }

    private static void add(long[] a,long[] b) { for(int i=0;i<a.length;i++) a[i]+=b[i]; }
    private static Map<String,Long> metrics(long[] v) {
        return Map.of("input",v[0],"output",v[1],"cacheRead",v[2],"cacheWrite",v[3],"total",v[4]);
    }
    private static long number(JsonNode n,String field) {
        var value=n.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong()>=0 ? value.asLong():0;
    }
    private long[] parseUsage(String raw) {
        try {
            var n=json.readTree(raw);
            if(n==null || !n.isObject()) return null;
            boolean anthropic=n.has("input_tokens")||n.has("output_tokens");
            if(!anthropic && !n.has("prompt_tokens") && !n.has("completion_tokens")) return null;
            long in=number(n,anthropic?"input_tokens":"prompt_tokens");
            long out=number(n,anthropic?"output_tokens":"completion_tokens");
            long read=anthropic?number(n,"cache_read_input_tokens"):number(n.path("prompt_tokens_details"),"cached_tokens");
            long write=anthropic?number(n,"cache_creation_input_tokens"):0;
            return new long[]{in,out,read,write,in+out+(anthropic?read+write:0)};
        } catch(Exception ignored) { return null; }
    }
}
