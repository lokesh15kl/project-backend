package com.example.full.project.controller;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final MongoTemplate mongoTemplate;

    public HealthController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "UP");
        response.put("service", "backend");
        return response;
    }

    @GetMapping("/db")
    public Map<String, Object> dbHealth() {
        Document result = mongoTemplate.executeCommand(new Document("ping", 1));
        Object ok = result.get("ok");
        boolean connected = ok instanceof Number && ((Number) ok).doubleValue() >= 1.0;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", connected ? "UP" : "DOWN");
        response.put("database", connected ? "connected" : "unavailable");
        return response;
    }
}
