package com.healthclaim.casedesk.controller;

import com.healthclaim.casedesk.config.Actor;
import com.healthclaim.casedesk.model.ClaimType;
import com.healthclaim.casedesk.model.PartType;
import com.healthclaim.casedesk.service.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class MetaController {

    private final AuditService audit;

    public MetaController(AuditService audit) { this.audit = audit; }

    /** Claim types and which parts each one needs — the frontend renders from this. */
    @GetMapping("/meta")
    public Map<String, Object> meta() {
        List<Map<String, Object>> types = new ArrayList<>();
        for (ClaimType t : ClaimType.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", t.name());
            m.put("label", t.label());
            m.put("needsBillSource", t.needsBillSource());
            List<String> parts = new ArrayList<>();
            for (PartType p : t.fixedParts()) parts.add(p.label());
            m.put("parts", parts);
            List<String> keys = new ArrayList<>();
            for (PartType p : t.fixedParts()) keys.add(p.name());
            m.put("partKeys", keys);
            types.add(m);
        }
        Map<String, Object> partDefs = new LinkedHashMap<>();
        for (PartType p : PartType.values()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("label", p.label());
            d.put("verification", p.verification());
            partDefs.put(p.name(), d);
        }
        return Map.of("claimTypes", types, "partDefs", partDefs,
                "insurers", List.of("Star Health", "HDFC ERGO", "ICICI Lombard", "Niva Bupa", "Care Health",
                        "Bajaj Allianz", "New India Assurance", "United India", "National Insurance",
                        "Oriental Insurance", "Aditya Birla Health", "Tata AIG", "SBI General", "Reliance General"));
    }

    @GetMapping("/audit")
    @PreAuthorize("hasRole('MAIN_ADMIN')")
    public List<Map<String, Object>> audit(@AuthenticationPrincipal Actor a, @RequestParam(defaultValue = "300") int limit) {
        return audit.recent(limit);
    }
}
