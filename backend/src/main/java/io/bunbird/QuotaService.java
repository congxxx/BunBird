package io.bunbird;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

/** Queries only the configured provider's official endpoint; credentials never leave this service. */
@Service
public class QuotaService {
    private final Supplier<JsonNode> fetch;
    private final Clock clock;
    private final boolean supported;
    private Map<String,Object> lastGood;
    private Map<String,Object> cached;
    private long nextCheck;

    @Autowired
    public QuotaService(ObjectMapper json, @Value("${MODEL_BASE_URL:}") String base,
            @Value("${MODEL_API_KEY:}") String key) {
        this.clock=Clock.systemUTC();
        String host="";
        try { host=Objects.toString(URI.create(base).getHost(),""); } catch(IllegalArgumentException ignored) {}
        String endpoint=switch(host) {
            case "api.minimaxi.com" -> "https://www.minimaxi.com/v1/token_plan/remains";
            case "api.minimax.io" -> "https://www.minimax.io/v1/token_plan/remains";
            default -> "";
        };
        supported=!endpoint.isEmpty() && !key.isBlank();
        var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NEVER).build();
        fetch=()->{
            try {
                var request=HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(8))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json").GET().build();
                var response=http.send(request,HttpResponse.BodyHandlers.ofString());
                if(response.statusCode()!=200) throw new IllegalStateException("Quota unavailable");
                return json.readTree(response.body());
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt(); throw new IllegalStateException("Quota interrupted");
            } catch(Exception e) { throw new IllegalStateException("Quota unavailable"); }
        };
    }

    QuotaService(Supplier<JsonNode> fetch,Clock clock,boolean supported) {
        this.fetch=fetch;this.clock=clock;this.supported=supported;
    }

    public synchronized Map<String,Object> get() {
        if(!supported) return Map.of("status","UNSUPPORTED","message","当前渠道尚无已接入的套餐额度接口。");
        long now=clock.millis();
        if(cached!=null && now<nextCheck) return cached;
        try {
            var body=fetch.get();
            if(body==null || !body.path("base_resp").path("status_code").isIntegralNumber()
                    || body.path("base_resp").path("status_code").asInt()!=0
                    || !body.path("model_remains").isArray()) throw new IllegalStateException();
            var windows=new ArrayList<Map<String,Object>>();
            for(var model:body.path("model_remains")) {
                String name=model.path("model_name").asText("通用模型");
                addWindow(windows,model,name,"interval","current_interval_remaining_percent","start_time","end_time");
                addWindow(windows,model,name,"weekly","current_weekly_remaining_percent","weekly_start_time","weekly_end_time");
            }
            if(windows.isEmpty()) throw new IllegalStateException();
            lastGood=Map.of("status","AVAILABLE","provider","MiniMax","plan","Token Plan",
                "windows",windows,"checkedAt",now,"source","官方套餐额度接口");
            cached=lastGood;
        } catch(Exception ignored) {
            var result=new LinkedHashMap<String,Object>();
            if(lastGood!=null) result.putAll(lastGood);
            result.put("status",lastGood==null?"UNAVAILABLE":"STALE");
            result.put("message",lastGood==null?"暂时无法查询套餐额度，请稍后重试。":"查询失败，以下为上次成功查询的数据。");
            cached=result;
        }
        nextCheck=clock.millis()+60_000;
        return cached;
    }

    private static void addWindow(List<Map<String,Object>> windows,JsonNode model,String name,
            String kind,String percentField,String startField,String endField) {
        var p=model.path(percentField);
        double remaining;
        try { remaining=Double.parseDouble(p.asText()); } catch(NumberFormatException e) { return; }
        if(!Double.isFinite(remaining) || remaining<0 || remaining>100) return;
        var window=new LinkedHashMap<String,Object>();
        window.put("model",name);window.put("kind",kind);window.put("remainingPercent",remaining);
        window.put("usedPercent",100-remaining);
        if(model.path(startField).canConvertToLong()) window.put("startsAt",model.path(startField).asLong());
        if(model.path(endField).canConvertToLong()) window.put("resetsAt",model.path(endField).asLong());
        windows.add(window);
    }
}

@RestController
class QuotaApi {
    private final AccountPool accounts;
    QuotaApi(AccountPool accounts) { this.accounts=accounts; }
    @GetMapping("/api/quota") public Object quota() {
        var list=(java.util.List<?>)accounts.snapshot().get("accounts");
        return list.isEmpty()?Map.of("status","UNSUPPORTED","message","当前租户尚未配置渠道"):((Map<?,?>)list.getFirst()).get("quota");
    }
}
