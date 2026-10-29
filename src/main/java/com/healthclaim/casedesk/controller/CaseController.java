package com.healthclaim.casedesk.controller;

import com.healthclaim.casedesk.config.Actor;
import com.healthclaim.casedesk.service.AuditService;
import com.healthclaim.casedesk.service.CaseService;
import com.healthclaim.casedesk.service.CaseService.CaseFilter;
import com.healthclaim.casedesk.service.Dto.*;
import com.healthclaim.casedesk.service.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private static final String STAFF = "hasAnyRole('MAIN_ADMIN','STATE_ADMIN','OFFICE_ASSISTANT')";

    private final CaseService cases;
    private final ExportService export;
    private final AuditService audit;

    public CaseController(CaseService cases, ExportService export, AuditService audit) {
        this.cases = cases; this.export = export; this.audit = audit;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Actor a,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String view,
                                          @RequestParam(required = false) String insurance,
                                          @RequestParam(required = false) String district,
                                          @RequestParam(required = false) String investigator,
                                          @RequestParam(required = false) String month,
                                          @RequestParam(required = false) String sortBy) {
        return cases.query(a, new CaseFilter(q, view, insurance, district, investigator, month, sortBy));
    }

    @GetMapping("/new-count")
    public Map<String, Integer> newCount(@AuthenticationPrincipal Actor a) {
        return Map.of("count", cases.newCaseCount(a));
    }

    /** Excel / PDF download of the (filtered, sorted) case list. Staff only. */
    @GetMapping("/export")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal Actor a,
                                         @RequestParam String format,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(required = false) String view,
                                         @RequestParam(required = false) String insurance,
                                         @RequestParam(required = false) String district,
                                         @RequestParam(required = false) String investigator,
                                         @RequestParam(required = false) String month,
                                         @RequestParam(required = false) String sortBy) {
        List<Map<String, Object>> rows = cases.query(a, new CaseFilter(q, view, insurance, district, investigator, month, sortBy));
        audit.log(a.username(), "CASES_EXPORT", format, rows.size() + " rows");
        boolean pdf = "pdf".equalsIgnoreCase(format);
        byte[] body = pdf ? export.pdf(rows, "Case list") : export.xlsx(rows);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(pdf ? "application/pdf"
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"cases." + (pdf ? "pdf" : "xlsx") + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    /** Open part-transfer requests (the alert badge in the admin / assistant login). Staff only. */
    @GetMapping("/part-transfers")
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> partTransfers(@AuthenticationPrincipal Actor a) {
        return cases.pendingTransfers(a);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@AuthenticationPrincipal Actor a, @PathVariable Long id) {
        return cases.get(a, id);
    }

    @PostMapping("/{id}/seen")
    public Map<String, String> markSeen(@AuthenticationPrincipal Actor a, @PathVariable Long id) {
        cases.markViewed(a, id);
        return Map.of("status", "ok");
    }

    @PostMapping
    @PreAuthorize(STAFF)
    public Map<String, Object> create(@AuthenticationPrincipal Actor a, @RequestBody CaseReq req) {
        return cases.create(a, req);
    }

    @PutMapping("/{id}")
    @PreAuthorize(STAFF)
    public Map<String, Object> update(@AuthenticationPrincipal Actor a, @PathVariable Long id, @RequestBody CaseReq req) {
        return cases.updateAssignment(a, id, req);
    }

    /** Investigator saves progress or submits (submit=true). */
    @PutMapping("/{id}/work")
    @PreAuthorize("hasRole('INVESTIGATOR')")
    public Map<String, Object> work(@AuthenticationPrincipal Actor a, @PathVariable Long id, @RequestBody SaveReq req) {
        return cases.investigatorSave(a, id, req);
    }

    @PostMapping("/{id}/send-back")
    @PreAuthorize(STAFF)
    public Map<String, Object> sendBack(@AuthenticationPrincipal Actor a, @PathVariable Long id, @RequestBody SendBackReq body) {
        return cases.sendBack(a, id, body);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize(STAFF)
    public Map<String, Object> complete(@AuthenticationPrincipal Actor a, @PathVariable Long id, @RequestBody CompleteReq body) {
        return cases.complete(a, id, body);
    }

    @PostMapping("/{id}/request-edit")
    @PreAuthorize("hasRole('INVESTIGATOR')")
    public Map<String, String> requestEdit(@AuthenticationPrincipal Actor a, @PathVariable Long id, @RequestBody NoteReq body) {
        cases.requestEdit(a, id, body.note());
        return Map.of("status", "requested");
    }

    @PostMapping("/{id}/edit-decision/{decision}")
    @PreAuthorize(STAFF)
    public Map<String, String> decide(@AuthenticationPrincipal Actor a, @PathVariable Long id, @PathVariable String decision) {
        cases.decideEdit(a, id, "approve".equals(decision));
        return Map.of("status", decision);
    }

    // ---------------------------------------------------------------- part transfer

    @PostMapping("/{id}/parts/{type}/transfer-request")
    @PreAuthorize("hasRole('INVESTIGATOR')")
    public Map<String, Object> transferRequest(@AuthenticationPrincipal Actor a, @PathVariable Long id,
                                               @PathVariable String type, @RequestBody TransferReq body) {
        return cases.requestTransfer(a, id, type, body);
    }

    @PostMapping("/{id}/parts/{type}/transfer-cancel")
    @PreAuthorize("hasRole('INVESTIGATOR')")
    public Map<String, Object> transferCancel(@AuthenticationPrincipal Actor a, @PathVariable Long id, @PathVariable String type) {
        return cases.cancelTransfer(a, id, type);
    }

    @PostMapping("/{id}/parts/{type}/transfer-decision")
    @PreAuthorize(STAFF)
    public Map<String, Object> transferDecision(@AuthenticationPrincipal Actor a, @PathVariable Long id,
                                                @PathVariable String type, @RequestBody TransferDecisionReq body) {
        return cases.decideTransfer(a, id, type, body);
    }
}
