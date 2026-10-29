package com.healthclaim.casedesk.service;

import com.healthclaim.casedesk.model.AuditLog;
import com.healthclaim.casedesk.repository.AuditRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class AuditService {

    private final AuditRepository repo;

    public AuditService(AuditRepository repo) { this.repo = repo; }

    public void log(String actor, String action, String target, String detail) {
        AuditLog a = new AuditLog();
        a.setActor(cut(actor, 80));
        a.setAction(cut(action, 60));
        a.setTarget(cut(target, 120));
        a.setDetail(cut(detail, 500));
        a.setIp(currentIp());
        repo.save(a);
    }

    public List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditLog a : repo.findAllByOrderByIdDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 1000))))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", a.getAt().toString());
            m.put("actor", a.getActor());
            m.put("action", a.getAction());
            m.put("target", a.getTarget());
            m.put("detail", a.getDetail());
            m.put("ip", a.getIp());
            out.add(m);
        }
        return out;
    }

    private static String currentIp() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes s) return s.getRequest().getRemoteAddr();
        return "-";
    }

    private static String cut(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }
}
