package io.bunbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Profiles contain environment variable names, never inline credentials. */
@Service
public class AccountPool {
    record Profile(String id,String name,String mode,String model,String baseUrl,String keyEnv,
                   String executor,boolean enabled) {
        Map<String,Object> wire() { return Map.of("id",id,"name",name,"mode",mode,"model",model,
            "baseUrl",baseUrl,"keyEnv",keyEnv,"executor",executor); }
    }
    private final List<Profile> profiles=new ArrayList<>();
    private final Map<String,QuotaService> quotas=new HashMap<>();
    private final Set<String> missing=new HashSet<>();
    private final JdbcTemplate db;
    private final String tenant;

    public AccountPool(Environment env,ObjectMapper json,JdbcTemplate db,QuotaService defaultQuota) {
        this.db=db;
        this.tenant=env.getProperty("bunbird.tenant","");
        String file=env.getProperty("BUNBIRD_ACCOUNTS_FILE","");
        String executor=env.getProperty("AGENT_EXECUTOR","model");
        if(file.isBlank()) {
            profiles.add(new Profile("environment","当前环境账号",env.getProperty("MODEL_MODE","mock"),
                env.getProperty("MODEL_NAME","mock-demo"),env.getProperty("MODEL_BASE_URL",""),
                "MODEL_API_KEY",executor,true));
            quotas.put("environment",defaultQuota);
        } else try {
            Path path=Path.of(file);
            if(Files.size(path)>65536) throw new IllegalArgumentException();
            var root=json.readTree(Files.readString(path));
            if(!root.isArray() || root.isEmpty() || root.size()>20) throw new IllegalArgumentException();
            var ids=new HashSet<String>();
            for(var item:root) {
                var fields=item.fieldNames();
                while(fields.hasNext()) if(!Set.of("id","name","mode","model","baseUrl","keyEnv","enabled").contains(fields.next()))
                    throw new IllegalArgumentException();
                var p=new Profile(item.path("id").asText(),item.path("name").asText(),
                    item.path("mode").asText(),item.path("model").asText(),
                    item.path("baseUrl").asText(),item.path("keyEnv").asText(),executor,
                    item.path("enabled").asBoolean(true));
                var uri=URI.create(p.baseUrl());
                if(!p.id().matches("[a-zA-Z0-9_-]{1,64}") || !ids.add(p.id())
                    || p.name().isBlank() || p.name().length()>80 || p.model().isBlank() || p.model().length()>128
                    || !p.keyEnv().matches("[A-Z][A-Z0-9_]{0,127}")
                    || !Set.of("anthropic","compatible").contains(p.mode())
                    || (executor.equals("claude") && !p.mode().equals("anthropic"))
                    || !Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null
                    || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || item.has("key") || item.has("apiKey")) throw new IllegalArgumentException();
                profiles.add(p);
                String key=env.getProperty(p.keyEnv(),"");
                if(key.isBlank()) missing.add(p.id());
                quotas.put(p.id(),new QuotaService(json,p.baseUrl(),key));
            }
        } catch(Exception e) { throw new IllegalArgumentException("Invalid account pool configuration; check profile fields (no inline keys)"); }
        for(var p:profiles) {
            if(!p.mode().equals("mock") && env.getProperty(p.keyEnv(),"").isBlank()) missing.add(p.id());
        }
    }

    Profile profile(String id) {
        return profiles.stream().filter(p->p.id().equals(id)).findFirst()
            .orElseThrow(()->new ResponseStatusException(BAD_REQUEST,"Unknown account"));
    }
    QuotaAdmission admission(Profile p) {
        if(!p.enabled() || missing.contains(p.id())) return new QuotaAdmission("BLOCK","ACCOUNT_UNCONFIGURED",null);
        var rows=db.queryForList("SELECT * FROM account_state WHERE id=?",p.id());
        if(!rows.isEmpty()) {
            var row=rows.getFirst();
            if(Boolean.TRUE.equals(row.get("disabled"))) return new QuotaAdmission("BLOCK","ACCOUNT_DISABLED",null);
            if("ACCOUNT_AUTH".equals(row.get("reason"))) return new QuotaAdmission("BLOCK","ACCOUNT_AUTH",null);
            long retry=((Number)row.get("retry_at")).longValue();
            if(retry>System.currentTimeMillis()) return new QuotaAdmission("BLOCK",(String)row.get("reason"),retry);
        }
        return QuotaAdmission.from(quotas.get(p.id()).get(),p.model(),System.currentTimeMillis());
    }
    public QuotaAdmission admission() {
        warm();
        boolean unknown=false; Long reset=null;
        for(var p:profiles) {
            var a=admission(p);
            if(a.state().equals("ALLOW")) return a;
            unknown|=a.state().equals("UNKNOWN");
            if(a.resumeAt()!=null) reset=reset==null?a.resumeAt():Math.min(reset,a.resumeAt());
        }
        return unknown?QuotaAdmission.unknown():new QuotaAdmission("BLOCK","ACCOUNT_POOL_UNAVAILABLE",reset);
    }
    public Map<String,Object> select(List<String> excluded) {
        warm();
        Profile unknown=null;
        for(var p:profiles) {
            if(excluded.contains(p.id())) continue;
            var a=admission(p);
            if(a.state().equals("ALLOW")) return p.wire();
            if(a.state().equals("UNKNOWN") && unknown==null) unknown=p;
        }
        return unknown==null?Map.of():unknown.wire();
    }
    private void ensureState(String id) {
        profile(id);
        db.update("INSERT INTO account_state(id,updated_at) VALUES(?,?) ON CONFLICT DO NOTHING",id,System.currentTimeMillis());
    }
    public synchronized void failure(String id,String reason) {
        if(!Set.of("ACCOUNT_AUTH","ACCOUNT_QUOTA","ACCOUNT_RATE_LIMIT").contains(reason))
            throw new ResponseStatusException(BAD_REQUEST);
        ensureState(id);
        long retry=System.currentTimeMillis()+(reason.equals("ACCOUNT_RATE_LIMIT")?60_000:300_000);
        db.update("UPDATE account_state SET reason=?,retry_at=?,failures=failures+1,updated_at=? WHERE id=?",
            reason,retry,System.currentTimeMillis(),id);
    }
    public synchronized void change(String id,String action) {
        if(!Set.of("enable","disable","reset").contains(action)) throw new ResponseStatusException(BAD_REQUEST);
        ensureState(id);
        if(action.equals("reset")) db.update("UPDATE account_state SET reason=NULL,retry_at=0,updated_at=? WHERE id=?",System.currentTimeMillis(),id);
        else db.update("UPDATE account_state SET disabled=?,updated_at=? WHERE id=?",action.equals("disable"),System.currentTimeMillis(),id);
    }
    public Map<String,Object> snapshot() {
        warm();
        var accounts=new ArrayList<Map<String,Object>>();
        for(var p:profiles) {
            var a=admission(p); var row=new LinkedHashMap<>(p.wire());
            row.remove("keyEnv");row.remove("baseUrl");
            row.put("status",a.state()); row.put("reason",Objects.toString(a.reason(),""));
            row.put("retryAt",a.resumeAt()==null?0:a.resumeAt());
            row.put("quota",quotas.get(p.id()).get());
            var states=db.queryForList("SELECT failures,disabled FROM account_state WHERE id=?",p.id());
            row.put("failures",states.isEmpty()?0:states.getFirst().get("failures"));
            row.put("disabled",!states.isEmpty() && Boolean.TRUE.equals(states.getFirst().get("disabled")));
            accounts.add(row);
        }
        return Map.of("accounts",accounts,"automaticSwitching",true,"configuredCount",profiles.size(),
            "switches",db.queryForList("SELECT e.task_id,e.detail,e.created_at FROM run_event e JOIN task t ON t.id=e.task_id WHERE t.tenant_id=? AND e.type='ACCOUNT_SWITCHED' ORDER BY e.created_at DESC LIMIT 10",tenant));
    }
    private void warm() {
        java.util.concurrent.CompletableFuture.allOf(profiles.stream()
            .filter(p->p.enabled() && !missing.contains(p.id()))
            .map(p->java.util.concurrent.CompletableFuture.runAsync(()->quotas.get(p.id()).get()))
            .toArray(java.util.concurrent.CompletableFuture[]::new)).join();
    }
}
