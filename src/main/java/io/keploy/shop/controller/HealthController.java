package io.keploy.shop.controller;

import com.mongodb.ConnectionString;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping
public class HealthController {

    private final MongoTemplate mongoTemplate;
    private final ConnectionString connectionString;

    public HealthController(MongoTemplate mongoTemplate, ConnectionString connectionString) {
        this.mongoTemplate = mongoTemplate;
        this.connectionString = connectionString;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", "tls-shop-api");
        m.put("time", Instant.now().toString());
        return m;
    }

    /** Actively pings MongoDB over the (TLS) connection and reports the round-trip. */
    @GetMapping("/api/health/db")
    public ResponseEntity<Map<String, Object>> db() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("database", mongoTemplate.getDb().getName());
        m.put("tls", Boolean.TRUE.equals(connectionString.getSslEnabled()));
        long start = System.nanoTime();
        try {
            Document ping = mongoTemplate.getDb().runCommand(new Document("ping", 1));
            m.put("ping", ping.get("ok"));
            m.put("latencyMs", (System.nanoTime() - start) / 1_000_000.0);
            m.put("status", "UP");
            return ResponseEntity.ok(m);
        } catch (Exception e) {
            m.put("status", "DOWN");
            m.put("error", e.getMessage());
            return ResponseEntity.status(503).body(m);
        }
    }
}
