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

/** Environment profiles and encrypted, dashboard-managed profiles share one live catalog. */
@Service
public class AccountPool {
    record Profile(String id,String name,String mode,String model,String baseUrl,String keyEnv,
                   String executor,boolean enabled,Map<String,String> modelMapping) {
        Profile(String id,String name,String mode,String model,String baseUrl,String keyEnv,String executor,boolean enabled) {
            this(id,name,mode,model,baseUrl,keyEnv,executor,enabled,Map.of());
        }
        Map<String,Object> wire() { return Map.of("id",id,"name",name,"mode",mode,"model",model,
            "baseUrl",baseUrl,"keyEnv",keyEnv,"executor",executor,"modelMapping",modelMapping); }
    }
    private final List<Profile> profiles=new ArrayList<>();
    private final Map<String,QuotaService> quotas=new HashMap<>();
    private final Set<String> missing=new HashSet<>();
    private final JdbcTemplate db;
    private final String tenant;
    private final ObjectMapper json;
    private final AccountVault vault;
    private final String executor;
    private final List<Profile> originalProfiles=new ArrayList<>();
    private final Map<String,String> originalSecrets=new HashMap<>(), secrets=new HashMap<>();
    private final Map<String,Integer> priorities=new HashMap<>();
    private List<Map<String,Object>> previousRows=List.of();
    private final java.util.concurrent.ConcurrentMap<String,CatalogEntry> modelCatalog=new java.util.concurrent.ConcurrentHashMap<>();
    private record CatalogEntry(Profile profile,String secret,List<String> models,long expires) {}
    public record ModelOption(String accountId,String accountName,String model) {
        String selector() { return accountId+":"+model; }
    }
    public List<ModelOption> modelOptions() {
        var view=view();
        var futures=view.profiles().stream().filter(p->p.enabled() && !view.missing().contains(p.id()))
            .filter(p->db.queryForObject("SELECT COUNT(*) FROM account_state WHERE id=? AND disabled=TRUE",Integer.class,p.id())==0)
            .map(p->java.util.concurrent.CompletableFuture.supplyAsync(()->{
                String secret=view.secrets().get(p.id());
                var cached=modelCatalog.get(p.id());
                if(cached==null || !cached.profile().equals(p) || !cached.secret().equals(secret) || cached.expires()<System.currentTimeMillis()) {
                    List<String> discovered=List.of();
                    try { if(!p.mode().equals("mock"))discovered=new ModelDiscovery(json).fetch(p.baseUrl(),secret,p.mode()); }
                    catch(ResponseStatusException ignored) {}
                    cached=new CatalogEntry(p,secret,discovered,System.currentTimeMillis()+60_000);
                    modelCatalog.put(p.id(),cached);
                }
                var models=new LinkedHashSet<String>();models.add(p.model());models.addAll(p.modelMapping().values());models.addAll(cached.models());
                return models.stream().limit(50).map(model->new ModelOption(p.id(),p.name(),model)).toList();
            }).completeOnTimeout(java.util.stream.Stream.concat(java.util.stream.Stream.of(p.model()),p.modelMapping().values().stream())
                .distinct().map(model->new ModelOption(p.id(),p.name(),model)).toList(),3,java.util.concurrent.TimeUnit.SECONDS)).toList();
        return futures.stream().flatMap(f->f.join().stream()).toList();
    }

    public AccountPool(Environment env,ObjectMapper json,JdbcTemplate db,QuotaService defaultQuota) {
        this.db=db; this.json=json; this.vault=new AccountVault(env);
        this.tenant=env.getProperty("bunbird.tenant","");
        String file=env.getProperty("BUNBIRD_ACCOUNTS_FILE","");
        this.executor=env.getProperty("AGENT_EXECUTOR","model");
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
            originalSecrets.put(p.id(),env.getProperty(p.keyEnv(),""));
            priorities.put(p.id(),priorities.size()*10);
            if(!p.mode().equals("mock") && env.getProperty(p.keyEnv(),"").isBlank()) missing.add(p.id());
        }
        originalProfiles.addAll(profiles);secrets.putAll(originalSecrets);
    }

    private synchronized void refresh() {
        var rows=db.queryForList("SELECT * FROM managed_account ORDER BY priority,id");
        if(rows.equals(previousRows)) return;
        var combined=new LinkedHashMap<String,Profile>();
        var nextSecrets=new HashMap<>(originalSecrets);
        var nextPriorities=new HashMap<String,Integer>();
        for(var p:originalProfiles) { combined.put(p.id(),p);nextPriorities.put(p.id(),nextPriorities.size()*10); }
        for(var row:rows) {
            String id=(String)row.get("id");
            combined.put(id,new Profile(id,(String)row.get("name"),(String)row.get("mode"),
                (String)row.get("model"),(String)row.get("base_url"),"",executor,true,readMapping((String)row.get("model_mapping"))));
            nextSecrets.put(id,vault.decrypt((String)row.get("secret_enc")));
            nextPriorities.put(id,((Number)row.get("priority")).intValue());
        }
        for(var p:combined.values()) {
            var old=profiles.stream().filter(q->q.id().equals(p.id())).findFirst().orElse(null);
            if(!p.equals(old) || !Objects.equals(nextSecrets.get(p.id()),secrets.get(p.id())))
                quotas.put(p.id(),new QuotaService(json,p.baseUrl(),nextSecrets.get(p.id())));
        }
        profiles.clear();profiles.addAll(combined.values());
        profiles.sort(Comparator.comparingInt(p->nextPriorities.get(p.id())));
        secrets.clear();secrets.putAll(nextSecrets);
        priorities.clear();priorities.putAll(nextPriorities);
        missing.clear();for(var p:profiles) if(!p.mode().equals("mock") && secrets.get(p.id()).isBlank()) missing.add(p.id());
        previousRows=rows;
    }

    private record View(List<Profile> profiles,Map<String,String> secrets,Map<String,QuotaService> quotas,
                        Map<String,Integer> priorities,Set<String> missing) {}
    private synchronized View view() {
        refresh();
        return new View(profiles.stream().filter(p->owned(p.id())).toList(),Map.copyOf(secrets),Map.copyOf(quotas),
            Map.copyOf(priorities),Set.copyOf(missing));
    }

    private String tenant() { return TenantContext.tenant(tenant); }
    private boolean owned(String id) {
        return previousRows.stream().filter(r->id.equals(r.get("id"))).findFirst()
            .map(r->tenant().equals(r.get("tenant_id"))).orElseGet(()->tenant().equals(tenant));
    }
    Profile profile(String id) {
        return profiles.stream().filter(p->owned(p.id()) && p.id().equals(id)).findFirst()
            .orElseThrow(()->new ResponseStatusException(BAD_REQUEST,"Unknown account"));
    }
    QuotaAdmission admission(Profile p,View view) {
        if(!p.enabled() || view.missing().contains(p.id())) return new QuotaAdmission("BLOCK","ACCOUNT_UNCONFIGURED",null);
        var rows=db.queryForList("SELECT * FROM account_state WHERE id=?",p.id());
        if(!rows.isEmpty()) {
            var row=rows.getFirst();
            if(Boolean.TRUE.equals(row.get("disabled"))) return new QuotaAdmission("BLOCK","ACCOUNT_DISABLED",null);
            if("ACCOUNT_AUTH".equals(row.get("reason"))) return new QuotaAdmission("BLOCK","ACCOUNT_AUTH",null);
            long retry=((Number)row.get("retry_at")).longValue();
            if(retry>System.currentTimeMillis()) return new QuotaAdmission("BLOCK",(String)row.get("reason"),retry);
        }
        return QuotaAdmission.from(view.quotas().get(p.id()).get(),p.model(),System.currentTimeMillis());
    }
    public QuotaAdmission admission() {
        var view=view();warm(view);
        boolean unknown=false; Long reset=null;
        for(var p:view.profiles()) {
            var a=admission(p,view);
            if(a.state().equals("ALLOW")) return a;
            unknown|=a.state().equals("UNKNOWN");
            if(a.resumeAt()!=null) reset=reset==null?a.resumeAt():Math.min(reset,a.resumeAt());
        }
        return unknown?QuotaAdmission.unknown():new QuotaAdmission("BLOCK","ACCOUNT_POOL_UNAVAILABLE",reset);
    }
    public Map<String,Object> select(List<String> excluded) {
        return select(excluded,null,null);
    }
    public Map<String,Object> selectDelegate(String accountId,String model) {
        var view=view();warm(view);
        for(var p:view.profiles()) {
            if(p.id().equals(accountId) || p.model().equalsIgnoreCase(model))continue;
            if(!Set.of("anthropic","compatible").contains(p.mode()))continue;
            if(admission(p,view).state().equals("ALLOW"))return selected(p,view);
        }
        return Map.of();
    }
    public Map<String,Object> select(List<String> excluded,String requestedAccount,String requestedModel) {
        var view=view();warm(view);
        if(requestedAccount!=null && requestedModel!=null && !excluded.contains(requestedAccount)) {
            var preferred=view.profiles().stream().filter(p->p.id().equals(requestedAccount)).findFirst().orElse(null);
            if(preferred!=null) {
                var mapped=new Profile(preferred.id(),preferred.name(),preferred.mode(),requestedModel,preferred.baseUrl(),preferred.keyEnv(),preferred.executor(),preferred.enabled(),preferred.modelMapping());
                if(!admission(mapped,view).state().equals("BLOCK"))return selected(mapped,view);
            }
        }
        Profile unknown=null;
        for(var p:view.profiles()) {
            if(excluded.contains(p.id()) || p.id().equals(requestedAccount)) continue;
            var a=admission(p,view);
            if(a.state().equals("ALLOW")) return selected(p,view);
            if(a.state().equals("UNKNOWN") && unknown==null) unknown=p;
        }
        return unknown==null?Map.of():selected(unknown,view);
    }
    private Map<String,Object> selected(Profile p,View view) {
        var result=new LinkedHashMap<>(p.wire());
        result.put("apiKey",view.secrets().get(p.id()));
        return result;
    }
    private Map<String,String> readMapping(String value) {
        try { return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,String>>() {}); }
        catch(Exception e) { throw new IllegalStateException("Invalid stored model mapping"); }
    }
    public record Discovery(String accountId,String baseUrl,String apiKey,String mode) {
        @Override public String toString() { return "ModelDiscovery[redacted]"; }
    }
    public Object discover(Discovery request) {
        String url=ModelDiscovery.normalize(request.baseUrl());
        String key=Objects.toString(request.apiKey(),"").trim();
        if(key.isEmpty()) {
            var view=view();
            var account=view.profiles().stream().filter(p->p.id().equals(request.accountId())).findFirst()
                .orElseThrow(()->new ResponseStatusException(BAD_REQUEST,"请填写 API 密钥"));
            if(!url.equals(ModelDiscovery.normalize(account.baseUrl())))
                throw new ResponseStatusException(BAD_REQUEST,"更换接口地址后请重新填写密钥");
            key=view.secrets().get(account.id());
        }
        return Map.of("models",new ModelDiscovery(json).fetch(url,key,request.mode()));
    }
    public record Edit(String name,String mode,String model,String baseUrl,String apiKey,int priority,Map<String,String> modelMapping) {
        public Edit(String name,String mode,String model,String baseUrl,String apiKey,int priority) {
            this(name,mode,model,baseUrl,apiKey,priority,null);
        }
        @Override public String toString() { return "AccountEdit[redacted]"; }
    }
    @org.springframework.transaction.annotation.Transactional
    public synchronized Map<String,Object> save(String requestedId,Edit edit) {
        // Serialize edits across Control instances, including the maximum account count.
        db.queryForList("SELECT id FROM account_catalog_guard WHERE id=1 FOR UPDATE");
        refresh();
        String id=requestedId==null?"account-"+UUID.randomUUID():requestedId;
        Profile old=requestedId==null?null:profile(id);
        String name=Objects.toString(edit.name(),"").trim(),mode=Objects.toString(edit.mode(),"").trim();
        String model=Objects.toString(edit.model(),"").trim(),url=Objects.toString(edit.baseUrl(),"").trim().replaceAll("/+$","");
        String key=Objects.toString(edit.apiKey(),"").trim();
        if(name.isBlank() || name.length()>80 || model.isBlank() || model.length()>128
            || url.length()>1024 || key.length()>8192 || edit.priority()<0 || edit.priority()>10000
            || !Set.of("anthropic","compatible").contains(mode)
            || (executor.equals("claude") && !mode.equals("anthropic")))
            throw new ResponseStatusException(BAD_REQUEST,"Invalid account fields");
        try {
            var uri=URI.create(url);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getQuery()!=null || uri.getFragment()!=null) throw new IllegalArgumentException();
        } catch(Exception e) { throw new ResponseStatusException(BAD_REQUEST,"Invalid endpoint"); }
        if(key.isBlank()) {
            if(old==null || !url.equals(old.baseUrl().replaceAll("/+$","")))
                throw new ResponseStatusException(BAD_REQUEST,"A key is required for a new endpoint");
            key=secrets.get(id);
        }
        if(key.isBlank() || key.chars().anyMatch(Character::isISOControl)) throw new ResponseStatusException(BAD_REQUEST,"Invalid key");
        if(old==null && view().profiles().size()>=20) throw new ResponseStatusException(CONFLICT,"Account pool is full");
        String encrypted=vault.encrypt(key);
        var mapping=new LinkedHashMap<String,String>();
        var supplied=edit.modelMapping()==null?(old==null?Map.<String,String>of():old.modelMapping()):edit.modelMapping();
        for(var entry:supplied.entrySet()) {
            String value=Objects.toString(entry.getValue(),"").trim();
            if(!Set.of("haiku","sonnet","opus","fable").contains(entry.getKey()) || value.length()>128
                || value.chars().anyMatch(Character::isISOControl))throw new ResponseStatusException(BAD_REQUEST,"Invalid model mapping");
            if(!value.isBlank())mapping.put(entry.getKey(),value);
        }
        String mappingJson;
        try { mappingJson=json.writeValueAsString(mapping); }
        catch(Exception e) { throw new ResponseStatusException(BAD_REQUEST,"Invalid model mapping"); }
        int changed=db.update("UPDATE managed_account SET name=?,mode=?,model=?,base_url=?,secret_enc=?,priority=?,updated_at=? WHERE id=?",
            name,mode,model,url,encrypted,edit.priority(),System.currentTimeMillis(),id);
        if(changed==0) db.update("INSERT INTO managed_account(id,name,mode,model,base_url,secret_enc,priority,updated_at,tenant_id) VALUES(?,?,?,?,?,?,?,?,?)",
            id,name,mode,model,url,encrypted,edit.priority(),System.currentTimeMillis(),tenant());
        db.update("UPDATE managed_account SET model_mapping=? WHERE id=?",mappingJson,id);
        // A supplied replacement key may repair authentication; admin disable stays in effect.
        if(edit.apiKey()!=null && !edit.apiKey().isBlank())
            db.update("UPDATE account_state SET reason=NULL,retry_at=0,updated_at=? WHERE id=?",System.currentTimeMillis(),id);
        return Map.of("id",id,"saved",true);
    }
    private void ensureState(String id) {
        refresh();
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
        var view=view();warm(view);
        var accounts=new ArrayList<Map<String,Object>>();
        for(var p:view.profiles()) {
            var a=admission(p,view); var row=new LinkedHashMap<>(p.wire());
            row.remove("keyEnv");
            row.put("priority",view.priorities().get(p.id()));
            row.put("hasKey",!view.secrets().get(p.id()).isBlank());
            row.put("status",a.state()); row.put("reason",Objects.toString(a.reason(),""));
            row.put("retryAt",a.resumeAt()==null?0:a.resumeAt());
            row.put("quota",view.quotas().get(p.id()).get());
            var states=db.queryForList("SELECT failures,disabled FROM account_state WHERE id=?",p.id());
            row.put("failures",states.isEmpty()?0:states.getFirst().get("failures"));
            row.put("disabled",!states.isEmpty() && Boolean.TRUE.equals(states.getFirst().get("disabled")));
            accounts.add(row);
        }
        return Map.of("accounts",accounts,"executor",executor,"automaticSwitching",true,"configuredCount",view.profiles().size(),
            "switches",db.queryForList("SELECT e.task_id,e.detail,e.created_at FROM run_event e JOIN task t ON t.id=e.task_id WHERE t.tenant_id=? AND e.type='ACCOUNT_SWITCHED' ORDER BY e.created_at DESC LIMIT 10",tenant()));
    }
    private void warm(View view) {
        java.util.concurrent.CompletableFuture.allOf(view.profiles().stream()
            .filter(p->p.enabled() && !view.missing().contains(p.id()))
            .map(p->java.util.concurrent.CompletableFuture.runAsync(()->view.quotas().get(p.id()).get()))
            .toArray(java.util.concurrent.CompletableFuture[]::new)).join();
    }
}
