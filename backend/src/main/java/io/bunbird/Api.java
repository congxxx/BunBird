package io.bunbird;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import static io.bunbird.Protocol.*;

@RestController
public class Api {
    private final TaskService tasks;
    private final QuotaService quota;
    private final String model;
    public Api(TaskService tasks, QuotaService quota, @org.springframework.beans.factory.annotation.Value("${MODEL_NAME:}") String model) {
        this.tasks = tasks; this.quota=quota; this.model=model;
    }
    @GetMapping("/health") public Map<String, String> health() { return Map.of("status", "ok", "service", "bunbird-control"); }
    @PostMapping("/internal/bridge/events") public Map<String, Object> receive(@Valid @RequestBody Incoming incoming) {
        try { return tasks.receive(incoming); }
        catch (DuplicateKeyException duplicate) { return tasks.duplicate(incoming); }
    }
    @GetMapping("/api/tasks") public Object list() { return tasks.tasks(); }
    @GetMapping("/api/tasks/{id}") public Object task(@PathVariable String id) { return tasks.task(id); }
    @GetMapping("/api/tasks/{id}/events") public Object events(@PathVariable String id, @RequestParam(defaultValue="0") long after) { return tasks.events(id, after); }
    @GetMapping("/api/tasks/{id}/deliveries") public Object deliveries(@PathVariable String id) { return tasks.deliveries(id); }
    @PostMapping("/api/tasks/{id}/cancel") public Object cancel(@PathVariable String id) { return tasks.cancel(id); }
    @PostMapping("/internal/worker/claim") public Object claim() {
        // Do network I/O before entering the claim transaction or taking any topic lock.
        if(!tasks.hasWaitingTasks()) return Map.of();
        return tasks.claim(QuotaAdmission.from(quota.get(),model,System.currentTimeMillis()));
    }
    @PostMapping("/internal/worker/tasks/{id}/heartbeat") public Object heartbeat(@PathVariable String id, @Valid @RequestBody Lease body) { return tasks.heartbeat(id, body.leaseToken()); }
    @PostMapping("/internal/worker/tasks/{id}/stopped") public Object stopped(@PathVariable String id, @Valid @RequestBody Lease body) { return tasks.stopped(id, body.leaseToken()); }
    @PostMapping("/internal/worker/tasks/{id}/complete") public Object complete(@PathVariable String id, @Valid @RequestBody Completion body) { return tasks.complete(id, body); }
    @PostMapping("/internal/worker/tasks/{id}/progress") public Object progress(@PathVariable String id, @Valid @RequestBody Progress body) { return tasks.progress(id, body); }
    @PostMapping("/internal/bridge/deliveries/claim") public Object claimDelivery() { return tasks.claimDelivery(); }
    @PostMapping("/internal/bridge/deliveries/{id}/ack") public void delivered(@PathVariable String id, @Valid @RequestBody Delivered body) { tasks.delivered(id, body); }
    @Scheduled(fixedDelay=5000) public void expire() { tasks.expire(); }
}
