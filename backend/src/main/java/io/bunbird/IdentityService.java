package io.bunbird;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class IdentityService {
    private final JdbcTemplate db;
    final String defaultTenant, defaultApp;
    private final String defaultBridge;
    private static final SecureRandom RANDOM=new SecureRandom();
    public IdentityService(JdbcTemplate db,@Value("${bunbird.tenant}") String tenant,
            @Value("${bunbird.app-id}") String app,@Value("${bunbird.bridge-token}") String bridge) {
        this.db=db;defaultTenant=tenant;defaultApp=app;defaultBridge=hash(bridge);
    }
    @jakarta.annotation.PostConstruct
    @Transactional
    public void migrate() {
        db.update("INSERT INTO organization(id,name,app_id,created_at) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",defaultTenant,"默认租户",defaultApp,System.currentTimeMillis());
        // A configured default is fixed once persisted. Never silently move data to a new default.
        db.update("UPDATE organization SET app_id=? WHERE id=?",defaultApp,defaultTenant);
        for(String table:List.of("managed_account","managed_skill","delivery"))
            db.update("UPDATE "+table+" SET tenant_id=? WHERE tenant_id IS NULL",defaultTenant);
    }
    public String defaultTenant() { return defaultTenant; }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    static String token() { byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    static String password(String value,String salt) {
        try {
            var spec=new PBEKeySpec(value.toCharArray(),Base64.getDecoder().decode(salt),210_000,256);
            try { return Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded()); }
            finally { spec.clearPassword(); }
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private String createUser(String username,String password,boolean platform) {
        username=Objects.toString(username,"").strip().toLowerCase(Locale.ROOT);
        if(!username.matches("[a-z0-9_.@-]{3,80}") || password==null || password.length()<12 || password.length()>256)
            throw new ResponseStatusException(BAD_REQUEST,"用户名需为 3–80 位字母、数字或 _.@-，密码至少 12 位");
        if(db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Integer.class,username)>0)
            throw new ResponseStatusException(CONFLICT,"用户名已存在，请登录后接受邀请");
        byte[] bytes=new byte[16];RANDOM.nextBytes(bytes);String salt=Base64.getEncoder().encodeToString(bytes);
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO app_user(id,username,password_hash,platform_admin,created_at) VALUES(?,?,?,?,?)",id,username,salt+":"+password(password,salt),platform,System.currentTimeMillis());
        return id;
    }
    private void lock() { db.queryForList("SELECT id FROM identity_guard WHERE id=1 FOR UPDATE"); }
    public boolean initialized() { return db.queryForObject("SELECT COUNT(*) FROM app_user WHERE platform_admin=TRUE",Integer.class)>0; }
    @Transactional public Map<String,Object> bootstrap(String username,String password) {
        lock();if(initialized())throw new ResponseStatusException(CONFLICT,"已完成初始化，请登录");
        String id=createUser(username,password,true);
        db.update("INSERT INTO membership VALUES(?,?,'ADMIN')",defaultTenant,id);
        return session(id);
    }
    public Map<String,Object> login(String username,String secret) { return session(verifyPassword(username,secret)); }
    private String verifyPassword(String username,String secret) {
        if(secret==null || secret.length()>256)throw new ResponseStatusException(UNAUTHORIZED,"用户名或密码错误");
        var rows=db.queryForList("SELECT id,password_hash FROM app_user WHERE username=?",Objects.toString(username,"").strip().toLowerCase(Locale.ROOT));
        String stored=rows.isEmpty()?"AAAAAAAAAAAAAAAAAAAAAA==:invalid":(String)rows.getFirst().get("password_hash");
        var parts=stored.split(":");
        if(!MessageDigest.isEqual(password(secret,parts[0]).getBytes(StandardCharsets.UTF_8),parts[1].getBytes(StandardCharsets.UTF_8)) || rows.isEmpty())
            throw new ResponseStatusException(UNAUTHORIZED,"用户名或密码错误");
        return (String)rows.getFirst().get("id");
    }
    private Map<String,Object> session(String user) {
        String token=token();long expiry=System.currentTimeMillis()+8*60*60*1000;
        db.update("DELETE FROM login_session WHERE expires_at<?",System.currentTimeMillis());
        db.update("INSERT INTO login_session VALUES(?,?,?)",hash(token),user,expiry);
        return Map.of("token",token,"expiresAt",expiry,"tenants",memberships(user));
    }
    public List<Map<String,Object>> memberships(String user) {
        return db.queryForList("SELECT o.id,o.name,m.role FROM membership m JOIN organization o ON o.id=m.tenant_id WHERE m.user_id=? ORDER BY o.created_at,o.id",user);
    }
    TenantContext.Identity authenticate(String token,String requestedTenant) {
        var rows=db.queryForList("SELECT u.id,u.platform_admin FROM login_session s JOIN app_user u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>?",hash(token),System.currentTimeMillis());
        if(rows.isEmpty())throw new ResponseStatusException(UNAUTHORIZED);
        var user=rows.getFirst();String id=(String)user.get("id");var memberships=memberships(id);
        var membership=memberships.stream().filter(m->requestedTenant==null || m.get("id").equals(requestedTenant)).findFirst()
            .orElseThrow(()->new ResponseStatusException(FORBIDDEN));
        return identity((String)membership.get("id"),id,(String)membership.get("role"),Boolean.TRUE.equals(user.get("platform_admin")));
    }
    TenantContext.Identity identity(String tenant,String user,String role,boolean platform) {
        var rows=db.queryForList("SELECT app_id FROM organization WHERE id=?",tenant);
        if(rows.isEmpty())throw new ResponseStatusException(FORBIDDEN);
        return new TenantContext.Identity(tenant,Objects.toString(rows.getFirst().get("app_id"),""),user,role,platform);
    }
    TenantContext.Identity bridge(String token) {
        String digest=hash(token);
        if(MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),defaultBridge.getBytes(StandardCharsets.UTF_8)))return identity(defaultTenant,"","ADMIN",false);
        var rows=db.queryForList("SELECT id FROM organization WHERE bridge_hash=? AND app_id IS NOT NULL",digest);
        if(rows.isEmpty())throw new ResponseStatusException(UNAUTHORIZED);
        return identity((String)rows.getFirst().get("id"),"","ADMIN",false);
    }
    TenantContext.Identity forTask(String taskId) {
        var rows=db.queryForList("SELECT tenant_id FROM task WHERE id=?",taskId);
        if(rows.isEmpty())throw new ResponseStatusException(NOT_FOUND);
        return identity((String)rows.getFirst().get("tenant_id"),"","ADMIN",false);
    }
    List<String> tenants() { return db.queryForList("SELECT id FROM organization ORDER BY created_at,id",String.class); }
    public void observe(String sender) {
        db.update("INSERT INTO feishu_identity(tenant_id,sender_id,last_seen) VALUES(?,?,?) ON CONFLICT DO NOTHING",TenantContext.current().tenant(),sender,System.currentTimeMillis());
        db.update("UPDATE feishu_identity SET last_seen=? WHERE tenant_id=? AND sender_id=?",System.currentTimeMillis(),TenantContext.current().tenant(),sender);
    }
    public Object me() {
        var current=TenantContext.current();
        return Map.of("userId",current.user(),"tenantId",current.tenant(),"role",current.role(),"platformAdmin",current.platform(),"tenants",memberships(current.user()));
    }
    public void logout(String token) { db.update("DELETE FROM login_session WHERE token_hash=?",hash(token)); }
    public Object members() { return db.queryForList("SELECT u.id,u.username,m.role FROM membership m JOIN app_user u ON u.id=m.user_id WHERE tenant_id=? ORDER BY u.username",TenantContext.current().tenant()); }
    @Transactional public Object invite(String role) {
        if(!Set.of("ADMIN","MEMBER").contains(Objects.toString(role,"")))throw new ResponseStatusException(BAD_REQUEST);
        String code=token();long expiry=System.currentTimeMillis()+72*60*60*1000;
        db.update("INSERT INTO invitation VALUES(?,?,?,?)",hash(code),TenantContext.current().tenant(),role,expiry);
        return Map.of("code",code,"expiresAt",expiry);
    }
    @Transactional public Object accept(String code,String username,String password,String existingUser) {
        lock();var rows=db.queryForList("SELECT * FROM invitation WHERE token_hash=? AND expires_at>?",hash(Objects.toString(code,"")),System.currentTimeMillis());
        if(rows.isEmpty())throw new ResponseStatusException(BAD_REQUEST,"邀请码无效或已过期");
        var invite=rows.getFirst();String user=existingUser;
        if(user==null) {
            boolean exists=db.queryForObject("SELECT COUNT(*) FROM app_user WHERE username=?",Integer.class,Objects.toString(username,"").strip().toLowerCase(Locale.ROOT))>0;
            user=exists?verifyPassword(username,password):createUser(username,password,false);
        }
        db.update("INSERT INTO membership VALUES(?,?,?) ON CONFLICT DO NOTHING",invite.get("tenant_id"),user,invite.get("role"));
        db.update("DELETE FROM invitation WHERE token_hash=?",hash(code));return session(user);
    }
    @Transactional public Object createTenant(String name,String requestedId) {
        if(!TenantContext.current().platform() || TenantContext.current().user().isEmpty())throw new ResponseStatusException(FORBIDDEN);
        if(name==null || name.isBlank() || name.length()>80)throw new ResponseStatusException(BAD_REQUEST);
        String id=Objects.toString(requestedId, "").strip();
        if(id.isEmpty())id=UUID.randomUUID().toString();
        if(!id.matches("[a-zA-Z0-9_-]{1,128}"))throw new ResponseStatusException(BAD_REQUEST);
        if(db.queryForObject("SELECT COUNT(*) FROM organization WHERE id=?",Integer.class,id)>0)throw new ResponseStatusException(CONFLICT,"租户已存在");
        db.update("INSERT INTO organization(id,name,created_at) VALUES(?,?,?)",id,name.strip(),System.currentTimeMillis());
        db.update("INSERT INTO membership VALUES(?,?,'ADMIN')",id,TenantContext.current().user());return Map.of("id",id);
    }
    @Transactional public Object remove(String user) {
        lock();String tenant=TenantContext.current().tenant();
        if(user.equals(TenantContext.current().user()))throw new ResponseStatusException(CONFLICT,"不能移除自己");
        db.update("DELETE FROM membership WHERE tenant_id=? AND user_id=?",tenant,user);return Map.of("removed",true);
    }
    @Transactional public Object binding(String app) {
        if(!TenantContext.current().platform() || TenantContext.current().user().isEmpty())throw new ResponseStatusException(FORBIDDEN);
        String tenant=TenantContext.current().tenant();
        if(tenant.equals(defaultTenant))throw new ResponseStatusException(CONFLICT,"默认租户的飞书连接仍由本机环境配置管理");
        if(app==null || !app.matches("cli_[a-zA-Z0-9_-]{1,124}"))throw new ResponseStatusException(BAD_REQUEST,"应用标识应以 cli_ 开头");
        var previous=db.queryForObject("SELECT app_id FROM organization WHERE id=?",String.class,tenant);
        if(previous!=null && !previous.equals(app))throw new ResponseStatusException(CONFLICT,"已绑定应用不能更换；可为同一应用重新生成连接凭据");
        String bridge=token();
        db.update("UPDATE organization SET app_id=?,bridge_hash=? WHERE id=?",app,hash(bridge),tenant);
        return Map.of("tenantId",tenant,"appId",app,"bridgeToken",bridge);
    }
}
