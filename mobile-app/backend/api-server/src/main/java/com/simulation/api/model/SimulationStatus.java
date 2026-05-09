package com.simulation.api.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SimulationStatus {
    private final String id;
    private final Map<String, Object> fields = new ConcurrentHashMap<>();

    public SimulationStatus(String id, String state) {
        this.id = id;
        fields.put("id", id);
        fields.put("state", state);
        fields.put("created", System.currentTimeMillis());
    }

    public SimulationStatus withField(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public String getId() { return id; }

    public String getState() {
        Object s = fields.get("state");
        return s != null ? s.toString() : "unknown";
    }

    public Map<String, Object> toMap() {
        return new LinkedHashMap<>(fields);
    }

    public String toJson() {
        return new SimulationResponse("ok")
                .withField("simulation", toMap())
                .toJson();
    }
}
