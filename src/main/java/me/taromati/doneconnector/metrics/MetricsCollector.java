package me.taromati.doneconnector.metrics;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MetricsCollector {
    private final String nickname;
    private final Map<String, Object> metrics = new ConcurrentHashMap<>();
    
    public MetricsCollector(String nickname) {
        this.nickname = nickname;
    }
    
    public void updateMetrics(String connectionState, int reconnectAttempts, 
                            long messageCount, int queueSize) {
        metrics.put("connectionState", connectionState);
        metrics.put("reconnectAttempts", reconnectAttempts);
        metrics.put("messageCount", messageCount);
        metrics.put("queueSize", queueSize);
        metrics.put("lastUpdated", System.currentTimeMillis());
    }
    
    public Map<String, Object> getMetrics() {
        return new HashMap<>(metrics);
    }
    
    public void shutdown() {
        metrics.clear();
    }
}