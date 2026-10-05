package io.bunbird;

import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class SkillApi {
    private final SkillHub skills;
    public SkillApi(SkillHub skills) { this.skills=skills; }
    @GetMapping("/api/skills") public Object list() { return skills.list(); }
    @GetMapping("/api/skills/{id}") public Object detail(@PathVariable String id) { return skills.detail(id); }
    public record State(boolean enabled) {}
    @PostMapping("/api/skills/{id}/state") public Object state(@PathVariable String id,@RequestBody State state) {
        skills.enabled(id,state.enabled());return Map.of("saved",true);
    }
    @PostMapping("/api/skills/import") public Object importSkill(@RequestBody SkillHub.Import request,jakarta.servlet.http.HttpServletResponse response) {
        try {
            if("local".equals(request.type()) && !TenantContext.current().platform())
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"本地目录导入仅对平台管理员开放");
            return skills.importSkill(request);
        }
        catch(ResponseStatusException e) { response.setStatus(e.getStatusCode().value());return Map.of("message",Objects.toString(e.getReason(),"导入失败")); }
    }
}
