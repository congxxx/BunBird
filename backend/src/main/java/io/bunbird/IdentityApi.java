package io.bunbird;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class IdentityApi {
    private final IdentityService identities;
    IdentityApi(IdentityService identities) { this.identities=identities; }
    public record Credentials(String username,String password,String code) {
        @Override public String toString() { return "Credentials[redacted]"; }
    }
    public record Tenant(String name,String id) {}
    public record Role(String role) {}
    public record Binding(String appId) {}
    @GetMapping("/api/auth/status") public Object status() { return Map.of("initialized",identities.initialized()); }
    @PostMapping("/api/auth/login") public Object login(@RequestBody Credentials body) { return identities.login(body.username(),body.password()); }
    @PostMapping("/api/auth/bootstrap") public Object bootstrap(@RequestBody Credentials body) { return identities.bootstrap(body.username(),body.password()); }
    @PostMapping("/api/auth/register") public Object register(@RequestBody Credentials body) { return identities.accept(body.code(),body.username(),body.password(),null); }
    @PostMapping("/api/auth/accept") public Object accept(@RequestBody Credentials body) { return identities.accept(body.code(),null,null,TenantContext.current().user()); }
    @GetMapping("/api/auth/me") public Object me() { return identities.me(); }
    @PostMapping("/api/auth/logout") public Object logout(@RequestHeader("Authorization") String authorization) { identities.logout(authorization.substring(7));return Map.of("loggedOut",true); }
    @GetMapping("/api/organization/members") public Object members() { return identities.members(); }
    @PostMapping("/api/organization/invitations") public Object invite(@RequestBody Role body) { return identities.invite(body.role()); }
    @DeleteMapping("/api/organization/members/{id}") public Object remove(@PathVariable String id) { return identities.remove(id); }
    @PostMapping("/api/organizations") public Object create(@RequestBody Tenant body) { return identities.createTenant(body.name(),body.id()); }
    @PostMapping("/api/organization/feishu") public Object bind(@RequestBody Binding body) { return identities.binding(body.appId()); }
}
