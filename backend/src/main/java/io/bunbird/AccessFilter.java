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
    private final String bridge, worker, admin;

    public AccessFilter(@Value("${bunbird.bridge-token}") String bridge,
                        @Value("${bunbird.worker-token}") String worker,
                        @Value("${bunbird.admin-token}") String admin) {
        if (bridge.length() < 32 || worker.length() < 32 || admin.length() < 32
                || Set.of(bridge, worker, admin).size() != 3) {
            throw new IllegalArgumentException("Configure three distinct BunBird tokens of at least 32 characters");
        }
        this.bridge = bridge; this.worker = worker; this.admin = admin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path.equals("/health") && req.getMethod().equals("GET")) { chain.doFilter(req, res); return; }
        String expected = path.startsWith("/internal/worker/") ? worker
                : path.startsWith("/internal/bridge/") ? bridge : admin;
        String actual = req.getHeader("Authorization");
        if (actual == null || !MessageDigest.isEqual(("Bearer " + expected).getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8))) {
            res.setStatus(401); res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"unauthorized\"}"); return;
        }
        chain.doFilter(req, res);
    }
}
