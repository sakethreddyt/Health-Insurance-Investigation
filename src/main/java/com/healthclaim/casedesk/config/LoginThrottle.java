package com.healthclaim.casedesk.config;

import org.springframework.stereotype.Component;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/** Per-IP brake on login attempts (on top of the per-account lockout). */
@Component
public class LoginThrottle {
    private static final int MAX = 30;
    private static final long WINDOW_MS = 10 * 60 * 1000L;
    private final ConcurrentHashMap<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean allow(String ip) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) q.pollFirst();
            if (q.size() >= MAX) return false;
            q.addLast(now);
            return true;
        }
    }
}
