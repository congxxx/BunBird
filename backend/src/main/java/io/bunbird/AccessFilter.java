package io.bunbird;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Separate capabilities: an executor cannot impersonate a Feishu user or send messages. */
@Component
public class AccessFilter extends OncePerRequestFilter {
    private final String worker, admin;
    private final IdentityService identities;
    private final java.util.Map<String,long[]> attempts=new java.util.HashMap<>();

    public AccessFilter(@Value("${bunbird.bridge-token}") String bridge,
                        @Value("${bunbird.worker-token}") String worker,
                        @Value("${bunbird.admin-token}") String admin, IdentityService identities) {
        if (bridge.length() < 32 || worker.length() < 32 || admin.length() < 32
                || Set.of(bridge, worker, admin).size() != 3) {
            throw new IllegalArgumentException("Configure three distinct BunBird tokens of at least 32 characters");
        }
        this.identities=identities; this.worker = worker; this.admin = admin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (Set.of("/dashboard.html", "/dashboard.css", "/dashboard.js").contains(path)
                && req.getMethod().equals("GET")) { chain.doFilter(req, res); return; }
        if (path.equals("/health") && req.getMethod().equals("GET")) { chain.doFilter(req, res); return; }
        res.setHeader("Cache-Control","no-store");
        try {
            if(Set.of("/api/auth/login","/api/auth/register","/api/auth/status").contains(path)) {
                if(!path.endsWith("/status") && !allowAttempt(req.getRemoteAddr())) { res.setStatus(429);return; }
                chain.doFilter(req,res);return;
            }
            String authorization=req.getHeader("Authorization");
            if(authorization==null || !authorization.startsWith("Bearer "))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
            String token=authorization.substring(7);
            TenantContext.Identity identity;
            if(path.startsWith("/internal/bridge/")) identity=identities.bridge(token);
            else if(path.startsWith("/internal/worker/")) {
                if(!same(token,worker))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
                String[] parts=path.split("/");
                identity=parts.length>4 && parts[3].equals("tasks")?identities.forTask(parts[4]):identities.identity(identities.defaultTenant(),"","ADMIN",false);
            } else if(same(token,admin)) {
                // Existing administrative automation remains limited to the default tenant.
                if(req.getHeader("X-Tenant-ID")!=null && !identities.defaultTenant().equals(req.getHeader("X-Tenant-ID")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
                identity=identities.identity(identities.defaultTenant(),"","ADMIN",true);
            } else identity=identities.authenticate(token,req.getHeader("X-Tenant-ID"));
            if(path.equals("/api/auth/bootstrap") && !same(token,admin))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            if(path.startsWith("/api/") && !req.getMethod().equals("GET") && !path.startsWith("/api/auth/") && !identity.role().equals("ADMIN"))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN);
            try(var scope=new TenantContext(identity)) { chain.doFilter(req,res); }
        } catch(org.springframework.web.server.ResponseStatusException e) {
            res.setStatus(e.getStatusCode().value());res.setContentType("application/json");res.getWriter().write("{\"error\":\"access_denied\"}");
        }
    }
    private static boolean same(String a,String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8)); }
    private synchronized boolean allowAttempt(String address) {
        long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->e.getValue()[0]<now-60_000);
        if(!attempts.containsKey(address) && attempts.size()>=1024)return false;
        long[] state=attempts.computeIfAbsent(address,k->new long[]{now,0});return ++state[1]<=10;
    }
}
