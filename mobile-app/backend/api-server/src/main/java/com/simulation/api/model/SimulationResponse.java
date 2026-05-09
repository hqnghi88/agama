package com.simulation.api.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class SimulationResponse {
    private final Map<String, Object> fields = new LinkedHashMap<>();

    public SimulationResponse(String status) {
        fields.put("status", status);
    }

    public SimulationResponse withField(String key, Object value) {
        fields.put(key, value);
        return this;
    }

    public String toJson() {
        return fields.entrySet().stream()
                .map(e -> {
                    String key = "\"" + escapeJson(e.getKey()) + "\"";
                    String value = toJsonValue(e.getValue());
                    return key + ": " + value;
                })
                .collect(Collectors.joining(", ", "{", "}"));
    }

    private String toJsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String) return "\"" + escapeJson((String) value) + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return map.entrySet().stream()
                    .map(e -> "\"" + escapeJson(e.getKey()) + "\": " + toJsonValue(e.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof Iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) sb.append(", ");
                sb.append(toJsonValue(item));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        }
        return "\"" + escapeJson(value.toString()) + "\"";
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
