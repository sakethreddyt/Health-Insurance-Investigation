package com.healthclaim.casedesk.service;

import com.healthclaim.casedesk.config.Actor;
import com.healthclaim.casedesk.model.*;
import com.healthclaim.casedesk.repository.CaseRepository;
import com.healthclaim.casedesk.repository.UserRepository;
import com.healthclaim.casedesk.service.Dto.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
public class CaseService {

    public record CaseFilter(String q, String view, String insurance, String district,
                             String investigator, String month, String sortBy) {}

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Pattern TMC = Pattern.compile("^[A-Za-z0-9 ._/\\-]{1,60}$");

    /** Stored in cases.investigatorUsername while no investigator has been chosen yet (column is NOT NULL). */
    private static final String NO_INVESTIGATOR = "";

    private final CaseRepository cases;
    private final UserRepository users;
    private final AuditService audit;

    public CaseService(CaseRepository cases, UserRepository users, AuditService audit) {
        this.cases = cases; this.users = users; this.audit = audit;
    }
    private static String partOwner(CasePart p, CaseRecord c) {
        return p.getOwnerAdmin() == null ? c.getOwnerAdmin() : p.getOwnerAdmin();
    }
    private static boolean ownsPart(Actor a, CasePart p, CaseRecord c) {
        return a != null && a.owner() != null && a.owner().equals(partOwner(p, c));
    }
    private static boolean ownsAnyPart(Actor a, CaseRecord c) {
        return c.getParts().stream().anyMatch(p -> ownsPart(a, p, c));
    }
    private static boolean canManagePart(Actor a, CasePart p, CaseRecord c) {
        return a.role() == Role.MAIN_ADMIN || (a.role().isStaff() && ownsPart(a, p, c));
    }
    private static void requireOwnsPart(Actor a, CasePart p, CaseRecord c) {
        if (a.role() != Role.MAIN_ADMIN && !ownsPart(a, p, c)) throw forbidden();
    }

    // ================================================================ reading

    public List<Map<String, Object>> query(Actor a, CaseFilter f) {
        List<CaseRecord> base = visibleCases(a);
        String q = f.q() == null ? "" : f.q().trim().toLowerCase();
        List<CaseRecord> list = base.stream().filter(c -> {
            if (!q.isEmpty() && !(low(c.getTmcNumber()).contains(q) || low(c.getPatientName()).contains(q)
                    || low(c.getHospitalName()).contains(q) || low(investigatorNames(c, a)).contains(q)
                    || low(c.getVillage()).contains(q) || low(c.getHospitalDistrict()).contains(q))) return false;
            if (notBlank(f.insurance()) && !f.insurance().equalsIgnoreCase(nz(c.getInsuranceCompany()))) return false;
            if (notBlank(f.district()) && !f.district().equalsIgnoreCase(nz(c.getDistrict()))) return false;
            if (notBlank(f.investigator()) && a.role() == Role.MAIN_ADMIN && !involves(c, f.investigator())) return false;
            if (notBlank(f.month()) && !f.month().equals(YearMonth.from(c.getCreatedAt().atZone(IST)).toString())) return false;
            return matchesView(c, f.view(), a);
        }).collect(Collectors.toList());

        Comparator<CaseRecord> byCreated = Comparator.comparing(CaseRecord::getCreatedAt).reversed();
        Comparator<CaseRecord> cmp = switch (f.sortBy() == null ? "" : f.sortBy()) {
            case "insurance" -> Comparator.comparing((CaseRecord c) -> low(c.getInsuranceCompany())).thenComparing(byCreated);
            case "district" -> Comparator.comparing((CaseRecord c) -> low(c.getDistrict())).thenComparing(byCreated);
            case "investigator" -> Comparator.comparing((CaseRecord c) -> low(investigatorNames(c, a))).thenComparing(byCreated);
            case "month" -> Comparator.comparing((CaseRecord c) -> YearMonth.from(c.getCreatedAt().atZone(IST))).reversed()
                                      .thenComparing(Comparator.comparing((CaseRecord c) -> low(c.getTmcNumber())));
            default -> byCreated;
        };
        list.sort(cmp);
        return list.stream().map(c -> view(c, a)).collect(Collectors.toList());
    }

    public int newCaseCount(Actor a) {
        return (int) visibleCases(a).stream()
                .filter(c -> c.isNewCaseNotification() && !c.getViewedBy().contains(a.username()))
                .count();
    }

    public void markViewed(Actor a, Long id) {
        CaseRecord c = loadForViewing(a, id);
        if (c.isNewCaseNotification()) c.getViewedBy().add(a.username());
        cases.save(c);
    }

    private List<CaseRecord> visibleCases(Actor a) {
        List<CaseRecord> visible = switch (a.role()) {
            case MAIN_ADMIN -> cases.findAll();
            case INVESTIGATOR -> cases.findForInvestigator(a.username());
            case STATE_ADMIN, OFFICE_ASSISTANT -> cases.findVisibleToStateAdmin(a.owner(), a.username());
            default -> cases.findByOwnerAdmin(a.owner());
        };
        initializeLegacyPartOwners(visible);
        return visible;
    }

    public Map<String, Object> get(Actor a, Long id) {
        return view(loadForViewing(a, id), a);
    }

    private void initializeLegacyPartOwners(Collection<CaseRecord> records) {
        for (CaseRecord c : records) {
            for (CasePart p : c.getParts()) {
                if (p.getOwnerAdmin() != null) continue;
                String investigator = partInv(p, c);
                AppUser inv = investigator == null ? null : users.findById(investigator).orElse(null);
                p.setOwnerAdmin(inv != null && inv.getRole() == Role.INVESTIGATOR
                        ? inv.getOwnerAdmin() : c.getOwnerAdmin());
            }
        }
    }

    // ================================================================ staff: assign / edit

    public Map<String, Object> create(Actor a, CaseReq r) {
        requireStaff(a);
        CaseRecord c = new CaseRecord();
        c.setCreatedBy(a.username());
        applyAssignment(c, r);
        syncParts(c);
        String auditDetail;
        if (a.role() == Role.MAIN_ADMIN) {
            assignPartAdmins(c, r);
            c.setInvestigatorUsername(NO_INVESTIGATOR);
            c.setInvestigatorName(null);
            auditDetail = "routed by location to State Admins - investigators not assigned yet";
        } else if (needsCrossStateRouting(a, c)) {
            c.setOwnerAdmin(a.owner());
            assignPartAdmins(c, r, a.owner());
            assignParts(a, c, r, true);
            auditDetail = "routed by location to State Admins; local parts assigned to " + investigatorNames(c);
        } else {
            c.setOwnerAdmin(a.owner());
            c.getParts().forEach(p -> p.setOwnerAdmin(a.owner()));
            assignParts(a, c, r, true);
            auditDetail = "to " + investigatorNames(c);
        }
        if (cases.existsByOwnerAdminAndTmcNumberIgnoreCase(c.getOwnerAdmin(), c.getTmcNumber()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A case with this TMCID already exists for this State Admin.");
        cases.save(c);
        audit.log(a.username(), "CASE_ASSIGN", c.getTmcNumber(), auditDetail);
        return view(c, a);
    }

    public Map<String, Object> updateAssignment(Actor a, Long id, CaseReq r) {
        requireStaff(a);
        CaseRecord c = load(a, id);
        if (c.getStatus() == CaseStatus.COMPLETED) throw bad("A completed case cannot be edited. Send it back first.");
        boolean crossAdminCase = c.getParts().stream().map(p -> partOwner(p, c)).distinct().count() > 1;
        if (a.role() != Role.MAIN_ADMIN && crossAdminCase) {
            assignParts(a, c, r, false);
            if (c.getStatus() == CaseStatus.SUBMITTED && !allSubmitted(c)) c.setStatus(CaseStatus.ASSIGNED);
            c.setUpdatedAt(Instant.now());
            cases.save(c);
            audit.log(a.username(), "CASE_PART_ASSIGN", c.getTmcNumber(), "updated assignments for State Admin-owned parts");
            return view(c, a);
        }
        String oldTmc = c.getTmcNumber();
        String oldOwner = c.getOwnerAdmin();
        applyAssignment(c, r);
        syncParts(c);
        String auditDetail = null;
        if (a.role() == Role.MAIN_ADMIN) {
            assignPartAdmins(c, r);
            if ((!oldTmc.equalsIgnoreCase(c.getTmcNumber()) || !Objects.equals(oldOwner, c.getOwnerAdmin()))
                    && cases.existsByOwnerAdminAndTmcNumberIgnoreCase(c.getOwnerAdmin(), c.getTmcNumber()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A case with this TMCID already exists.");
        }
        if (a.role() != Role.MAIN_ADMIN) assignParts(a, c, r, false);
        if (c.getStatus() == CaseStatus.SUBMITTED && !allSubmitted(c)) c.setStatus(CaseStatus.ASSIGNED);
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        audit.log(a.username(), "CASE_EDIT", c.getTmcNumber(), auditDetail);
        return view(c, a);
    }

    // ================================================================ investigator: fill & submit (own parts only)

    public Map<String, Object> investigatorSave(Actor a, Long id, SaveReq r) {
        if (a.role() != Role.INVESTIGATOR) throw forbidden();
        CaseRecord c = load(a, id);
        if (c.getStatus() != CaseStatus.ASSIGNED && c.getStatus() != CaseStatus.SENT_BACK)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This case is locked. Ask your admin to send it back or approve an edit request.");

        Map<PartType, CasePart> mine = new EnumMap<>(PartType.class);
        for (CasePart p : c.getParts()) if (a.username().equals(partInv(p, c))) mine.put(p.getType(), p);

        if (r.parts() != null) {
            for (PartReq pr : r.parts()) {
                PartType t;
                try { t = PartType.valueOf(pr.type()); } catch (Exception e) { throw bad("Unknown part."); }
                CasePart p = mine.get(t);
                if (p == null) throw bad("That part is not assigned to you.");
                if (p.isSubmitted()) throw bad(p.getType().label() + " is already submitted. Ask your admin to send it back to change it.");
                String ans = pr.answer() == null ? "" : pr.answer().trim().toUpperCase();
                if (!(ans.isEmpty() || ans.equals("Y") || ans.equals("N"))) throw bad("Answer must be Yes or No.");
                p.setAnswer(ans.isEmpty() ? null : ans);
                p.setReason("N".equals(ans) ? opt(pr.reason(), 1000) : null);
                p.setViaOther("Y".equals(ans) && Boolean.TRUE.equals(pr.viaOther()));
                p.setNote(opt(pr.note(), 1000));
                if (p.getType() == PartType.HOSPITAL && c.getClaimType() != ClaimType.CASHLESS) {
                    p.setMrdBillAvailable(pr.mrdBillAvailable());
                    if (Boolean.TRUE.equals(pr.mrdBillAvailable()) && pr.mrdBillAmount() != null
                            && (pr.mrdBillAmount().signum() < 0 || pr.mrdBillAmount().precision() > 12
                            || pr.mrdBillAmount().scale() > 2))
                        throw bad("MRD charges must be a non-negative amount with at most two decimal places.");
                    p.setMrdBillAmount(Boolean.TRUE.equals(pr.mrdBillAvailable()) ? pr.mrdBillAmount() : null);
                } else {
                    p.setMrdBillAvailable(null);
                    p.setMrdBillAmount(null);
                }
            }
        }
        List<CasePart> editable = mine.values().stream().filter(p -> !p.isSubmitted()).collect(Collectors.toList());
        String myRemarks = opt(r.remarks(), 2000);
        for (CasePart p : editable) p.setRemarks(myRemarks);
        c.setRemarks(combinedRemarks(c));
        if (r.claimNo() != null) c.setClaimNo(opt(r.claimNo(), 60));
        if (r.policyNo() != null) c.setPolicyNo(opt(r.policyNo(), 60));
        if (r.patientAge() != null) c.setPatientAge(opt(r.patientAge(), 10));
        if (r.ipNo() != null) c.setIpNo(opt(r.ipNo(), 40));

        if (r.submit()) {
            if (editable.isEmpty()) throw bad("There is nothing left for you to submit on this case.");
            for (CasePart p : editable) {
                String label = p.getType().label();
                if (p.getTransferStatus() != null)
                    throw bad("A transfer request is pending for: " + label + ". Wait for the admin's decision or cancel the request first.");
                if (p.getAnswer() == null) throw bad("Please choose Yes or No for: " + label + ".");
                if ("N".equals(p.getAnswer()) && p.getReason() == null) throw bad("Please give the reason for 'No' on: " + label + ".");
                if (p.isViaOther() && p.getNote() == null) throw bad("Please mention in remarks who will send the documents for: " + label + ".");
                if (p.getType() == PartType.HOSPITAL && c.getClaimType() != ClaimType.CASHLESS) {
                    if (p.getMrdBillAvailable() == null)
                        throw bad("Please indicate whether the MRD charges bill is available.");
                    if (Boolean.TRUE.equals(p.getMrdBillAvailable())
                            && (p.getMrdBillAmount() == null || p.getMrdBillAmount().signum() <= 0))
                        throw bad("Enter the MRD charges amount when the bill is available.");
                }
            }
            for (CasePart p : editable) p.setSubmitted(true);
            boolean done = allSubmitted(c);
            if (done) {
                c.setFlagged(computeFlagged(c));
                c.setStatus(CaseStatus.SUBMITTED);
                c.setSubmittedAt(Instant.now());
            }
            audit.log(a.username(), "CASE_SUBMIT", c.getTmcNumber(),
                    (done ? "case complete for review" : "own part(s) submitted; waiting for other investigator(s)") + (computeFlagged(c) ? " - with remarks/exceptions" : ""));
        }
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        return view(c, a);
    }

    public void requestEdit(Actor a, Long id, String reason) {
        if (a.role() != Role.INVESTIGATOR) throw forbidden();
        CaseRecord c = load(a, id);
        boolean reviewedPart = c.getParts().stream()
                .anyMatch(p -> a.username().equals(partInv(p, c)) && p.isReviewed());
        if (c.getStatus() != CaseStatus.COMPLETED && !reviewedPart)
            throw bad("You can request an edit after your part has been reviewed.");
        String editReason = req(reason, "Please explain why you need to edit this case", 1000);
        c.setEditRequested(true);
        c.setEditRequestedAt(Instant.now());
        c.setEditRequestedBy(a.username());
        c.setEditRequestReason(editReason);
        cases.save(c);
        audit.log(a.username(), "EDIT_REQUEST", c.getTmcNumber(), c.getEditRequestReason());
    }

    // ================================================================ part transfer (investigator asks, staff decides)

    public Map<String, Object> requestTransfer(Actor a, Long id, String typeName, TransferReq r) {
        if (a.role() != Role.INVESTIGATOR) throw forbidden();
        CaseRecord c = load(a, id);
        CasePart p = partOf(c, typeName);
        if (!a.username().equals(partInv(p, c))) throw bad("That part is not assigned to you.");
        if (c.getStatus() == CaseStatus.COMPLETED || p.isSubmitted()) throw bad("A submitted part cannot be transferred.");
        if (p.getTransferStatus() != null) throw bad("A transfer request is already pending for this part.");
        p.setTransferReason(req(r.reason(), "Please give the reason for the transfer", 1000));
        p.setTransferTarget(opt(r.target(), 200));
        p.setTransferStatus("REQUESTED");
        p.setTransferRequestedAt(Instant.now());
        p.setTransferDecisionNote(null);
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        audit.log(a.username(), "PART_TRANSFER_REQUEST", c.getTmcNumber(), p.getType().label() + ": " + p.getTransferReason());
        return view(c, a);
    }

    public Map<String, Object> cancelTransfer(Actor a, Long id, String typeName) {
        if (a.role() != Role.INVESTIGATOR) throw forbidden();
        CaseRecord c = load(a, id);
        CasePart p = partOf(c, typeName);
        if (!a.username().equals(partInv(p, c))) throw bad("That part is not assigned to you.");
        if (p.getTransferStatus() == null) throw bad("No transfer request on this part.");
        p.clearTransfer();
        cases.save(c);
        audit.log(a.username(), "PART_TRANSFER_CANCEL", c.getTmcNumber(), p.getType().label());
        return view(c, a);
    }

    /** All open transfer requests the actor can see (staff only). canAct tells whether this actor may decide it. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingTransfers(Actor a) {
        requireStaff(a);
        List<CaseRecord> base = a.role() == Role.MAIN_ADMIN ? cases.findAll()
                : cases.findVisibleToStateAdmin(a.owner(), a.username());
        initializeLegacyPartOwners(base);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CaseRecord c : base) {
            for (CasePart p : c.getParts()) {
                if (a.role() != Role.MAIN_ADMIN && !ownsPart(a, p, c)) continue;
                if (p.getTransferStatus() == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("caseId", c.getId());
                m.put("tmcNumber", c.getTmcNumber());
                m.put("patientName", c.getPatientName());
                m.put("patientDistrict", c.getDistrict());
                m.put("patientState", c.getPatientState());
                m.put("village", c.getVillage());
                m.put("hospitalName", c.getHospitalName());
                m.put("hospitalDistrict", c.getHospitalDistrict());
                m.put("hospitalState", c.getHospitalState());
                m.put("policeStationState", c.getPoliceStationState());
                m.put("policeStationDistrict", c.getPoliceStationDistrict());
                m.put("partType", p.getType().name());
                m.put("partLabel", p.getType().label());
                m.put("fromUsername", partInv(p, c));
                m.put("fromName", partInvName(p, c));
                m.put("reason", p.getTransferReason());
                m.put("target", p.getTransferTarget());
                m.put("requestedAt", str(p.getTransferRequestedAt()));
                m.put("status", p.getTransferStatus());
                m.put("canAct", a.role() == Role.MAIN_ADMIN || ownsPart(a, p, c));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("requestedAt"))));
        return out;
    }

    public Map<String, Object> decideTransfer(Actor a, Long id, String typeName, TransferDecisionReq r) {
        requireStaff(a);
        CaseRecord c = load(a, id);
        CasePart p = partOf(c, typeName);
        requireOwnsPart(a, p, c);
        if (p.getTransferStatus() == null) throw bad("There is no transfer request on this part.");
        if ("FORWARDED".equals(p.getTransferStatus()) && a.role() != Role.MAIN_ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This request was forwarded to the Main Admin. Only the Main Admin can decide it.");
        String decision = r.decision() == null ? "" : r.decision().trim().toLowerCase();
        String label = p.getType().label();
        switch (decision) {
            case "approve" -> {
                AppUser inv = findInvestigator(a, r.investigatorUsername());
                if (!Objects.equals(inv.getOwnerAdmin(), partOwner(p, c)))
                    throw bad("Choose an investigator assigned to this part's State Admin.");
                String from = partInvName(p, c);
                if (inv.getUsername().equals(partInv(p, c))) throw bad("Choose a different investigator for this part.");
                moveTo(p, inv);
                p.clearTransfer();
                p.setTransferDecisionNote(null);
                p.setTransferHistory(trim1000(join(p.getTransferHistory(), "Transferred from " + from + " to " + inv.getFullName()
                        + " on " + LocalDate.now(IST) + " by " + a.username())));
                if (c.getStatus() == CaseStatus.SUBMITTED) c.setStatus(CaseStatus.ASSIGNED);
                refreshPrimary(c);
                audit.log(a.username(), "PART_TRANSFER_APPROVE", c.getTmcNumber(), label + ": " + from + " -> " + inv.getUsername());
            }
            case "deny" -> {
                p.clearTransfer();
                p.setTransferDecisionNote(req(r.note(), "Please give a reason for denying the transfer", 1000));
                audit.log(a.username(), "PART_TRANSFER_DENY", c.getTmcNumber(), label + ": " + p.getTransferDecisionNote());
            }
            case "forward" -> {
                if (a.role() == Role.MAIN_ADMIN) throw bad("You are the Main Admin - please choose an investigator instead.");
                p.setTransferStatus("FORWARDED");
                audit.log(a.username(), "PART_TRANSFER_FORWARD", c.getTmcNumber(), label + " forwarded to Main Admin");
            }
            default -> throw bad("Decision must be approve, deny or forward.");
        }
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        return view(c, a);
    }

    // ================================================================ staff: review actions

    public Map<String, Object> sendBack(Actor a, Long id, SendBackReq r) {
        requireStaff(a);
        CaseRecord c = load(a, id);
        String reason = req(r.note(), "Please say why the case is being sent back", 1000);
        Set<PartType> chosen = EnumSet.noneOf(PartType.class);
        if (r.parts() != null) {
            for (String n : r.parts()) {
                try { chosen.add(PartType.valueOf(n)); } catch (Exception e) { throw bad("Unknown part."); }
            }
        }
        boolean all = chosen.isEmpty();
        List<String> reopened = new ArrayList<>();
        for (CasePart p : c.getParts()) {
            if (!canManagePart(a, p, c)) continue;
            if (all || chosen.contains(p.getType())) {
                p.setSubmitted(false);
                p.setReviewed(false);
                p.setReviewedBy(null);
                p.setReviewedAt(null);
                p.setReviewNote(null);
                reopened.add(p.getType().label());
            }
        }
        if (reopened.isEmpty()) throw bad("None of the selected parts belong to this case.");
        c.setStatus(CaseStatus.SENT_BACK);
        c.setSentBackReason(reason);
        c.setSentBackBy(a.username());
        c.setSentBackAt(Instant.now());
        c.setEditRequested(false);
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        audit.log(a.username(), "CASE_SEND_BACK", c.getTmcNumber(), String.join(", ", reopened) + ": " + c.getSentBackReason());
        return view(c, a);
    }

    public Map<String, Object> complete(Actor a, Long id, CompleteReq r) {
        requireStaff(a);
        CaseRecord c = load(a, id);
        if (a.role() != Role.MAIN_ADMIN) {
            List<CasePart> teamParts = c.getParts().stream().filter(p -> ownsPart(a, p, c)).toList();
            if (teamParts.isEmpty()) throw forbidden();
            if (teamParts.stream().anyMatch(p -> !p.isSubmitted()))
                throw bad("Your team can review only after its investigators submit every assigned part.");
            if (teamParts.stream().allMatch(CasePart::isReviewed))
                throw bad("Your team's submitted parts have already been reviewed.");
            if (teamParts.stream().anyMatch(CaseService::partFlagged) && !r.ack())
                throw bad("Your team's parts have remarks / exceptions. Confirm you reviewed them and the documents are received.");
            String note = opt(r.note(), 1000);
            Instant reviewedAt = Instant.now();
            for (CasePart p : teamParts) {
                p.setReviewed(true);
                p.setReviewedBy(a.username());
                p.setReviewedAt(reviewedAt);
                p.setReviewNote(note);
            }
            if (allSubmitted(c) && allReviewed(c)) {
                c.setStatus(CaseStatus.COMPLETED);
                c.setReviewNote(note);
                c.setReviewedBy(a.username());
                c.setReviewedAt(reviewedAt);
            }
            c.setUpdatedAt(Instant.now());
            cases.save(c);
            audit.log(a.username(), "CASE_TEAM_REVIEW", c.getTmcNumber(),
                    "reviewed " + teamParts.size() + " assigned part(s)" + (note == null ? "" : ": " + note));
            return view(c, a);
        }
        if (c.getStatus() != CaseStatus.SUBMITTED || !allSubmitted(c))
            throw bad("Only a fully submitted case can be marked complete.");
        boolean crossAdminCase = c.getParts().stream().map(p -> partOwner(p, c)).distinct().count() > 1;
        if (crossAdminCase && !allReviewed(c))
            throw bad("Each State Admin team must review its own parts before this case can be completed.");
        if (c.isFlagged() && !r.ack())
            throw bad("This case has remarks / exceptions. Tick the box to confirm you reviewed them and the documents are received.");
        Instant reviewedAt = Instant.now();
        for (CasePart p : c.getParts()) {
            p.setReviewed(true);
            p.setReviewedBy(a.username());
            p.setReviewedAt(reviewedAt);
            p.setReviewNote(opt(r.note(), 1000));
        }
        c.setStatus(CaseStatus.COMPLETED);
        c.setReviewNote(opt(r.note(), 1000));
        c.setReviewedBy(a.username());
        c.setReviewedAt(reviewedAt);
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        audit.log(a.username(), "CASE_COMPLETE", c.getTmcNumber(), c.getReviewNote());
        return view(c, a);
    }

    public void decideEdit(Actor a, Long id, boolean approve) {
        requireStaff(a);
        CaseRecord c = load(a, id);
        if (a.role() != Role.MAIN_ADMIN && c.getParts().stream().noneMatch(p -> canManagePart(a, p, c)
                && (c.getEditRequestedBy() == null || c.getEditRequestedBy().equals(partInv(p, c))))) throw forbidden();
        if (!c.isEditRequested()) throw bad("No edit request on this case.");
        String requester = c.getEditRequestedBy();
        c.setEditRequested(false);
        c.setEditRequestedBy(null);
        if (approve) {
            for (CasePart p : c.getParts()) {
                if (!canManagePart(a, p, c) || (requester != null && !requester.equals(partInv(p, c)))) continue;
                p.setSubmitted(false);
                p.setReviewed(false);
                p.setReviewedBy(null);
                p.setReviewedAt(null);
                p.setReviewNote(null);
            }
            c.setStatus(CaseStatus.SENT_BACK);
            c.setSentBackReason("Edit request approved. You can update and re-submit this case.");
            c.setSentBackBy(a.username());
            c.setSentBackAt(Instant.now());
        }
        c.setUpdatedAt(Instant.now());
        cases.save(c);
        audit.log(a.username(), approve ? "EDIT_APPROVE" : "EDIT_DENY", c.getTmcNumber(), null);
    }

    // ================================================================ helpers

    private CaseRecord load(Actor a, Long id) {
        CaseRecord c = cases.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found."));
        initializeLegacyPartOwners(List.of(c));
        boolean ok = a.role() == Role.INVESTIGATOR ? involves(c, a.username())
                : a.role() == Role.MAIN_ADMIN || (a.role() == Role.STATE_ADMIN || a.role() == Role.OFFICE_ASSISTANT
                    ? ownsAnyPart(a, c) : a.canSee(c.getOwnerAdmin()));
        if (!ok) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found.");
        return c;
    }

    private CaseRecord loadForViewing(Actor a, Long id) {
        CaseRecord c = cases.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found."));
        initializeLegacyPartOwners(List.of(c));
        boolean ok = a.role() == Role.MAIN_ADMIN
                || (a.role() == Role.INVESTIGATOR ? involves(c, a.username())
                    : ownsAnyPart(a, c) || (a.role().isStaff() && Objects.equals(a.username(), c.getCreatedBy())));
        if (!ok) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Case not found.");
        return c;
    }

    private AppUser findStateAdmin(String username) {
        if (!notBlank(username)) throw bad("Please choose the State Admin for this case.");
        AppUser sa = users.findById(username.trim()).orElseThrow(() -> bad("Please choose the State Admin for this case."));
        if (sa.getRole() != Role.STATE_ADMIN) throw bad("Please choose the State Admin for this case.");
        if (!sa.isActive()) throw bad("That State Admin is not active.");
        return sa;
    }

    private void assignPartAdmins(CaseRecord c, CaseReq r) {
        assignPartAdmins(c, r, null);
    }

    private void assignPartAdmins(CaseRecord c, CaseReq r, String defaultAdmin) {
        AppUser hospitalAdmin = findStateAdmin(
                notBlank(r.hospitalStateAdmin()) ? r.hospitalStateAdmin()
                        : notBlank(r.stateAdmin()) ? r.stateAdmin() : defaultAdmin,
                c.getHospitalState(), "hospital");
        boolean hasPatientPart = c.getParts().stream().anyMatch(p -> p.getType() == PartType.PATIENT);
        AppUser patientAdmin = hasPatientPart
                ? findStateAdmin(notBlank(r.patientStateAdmin()) ? r.patientStateAdmin() : defaultAdmin,
                        c.getPatientState(), "patient") : hospitalAdmin;
        c.setOwnerAdmin(hospitalAdmin.getUsername());
        for (CasePart p : c.getParts()) {
            String target = p.getType() == PartType.PATIENT ? patientAdmin.getUsername() : hospitalAdmin.getUsername();
            if (!Objects.equals(partOwner(p, c), target)) {
                String previous = partInvName(p, c);
                if (previous != null) p.setTransferHistory(trim1000(join(p.getTransferHistory(),
                        "Part routed to State Admin " + target + " on " + LocalDate.now(IST) + " by Main Admin")));
                p.setOwnerAdmin(target);
                p.setInvestigatorUsername(null);
                p.setInvestigatorName(null);
                p.resetWork();
                p.clearTransfer();
            } else {
                p.setOwnerAdmin(target);
            }
        }
        c.setInvestigatorUsername(NO_INVESTIGATOR);
        c.setInvestigatorName(null);
    }

    private boolean needsCrossStateRouting(Actor a, CaseRecord c) {
        AppUser owner = users.findById(a.owner()).orElse(null);
        String ownerState = owner == null ? null : owner.getState();
        if (!normalizeState(c.getHospitalState()).equals(normalizeState(ownerState))) return true;
        return c.getParts().stream().anyMatch(p -> p.getType() == PartType.PATIENT
                && !normalizeState(c.getPatientState()).equals(normalizeState(ownerState)));
    }

    private AppUser findStateAdmin(String username, String state, String location) {
        AppUser sa = findStateAdmin(username, location);
        if (state == null || sa.getState() == null
                || !state.trim().equalsIgnoreCase(sa.getState().trim()))
            throw bad("Choose a State Admin assigned to the " + location + " state (" + state + ").");
        return sa;
    }

    private AppUser findStateAdmin(String username, String location) {
        if (!notBlank(username)) throw bad("Please choose the " + location + " State Admin.");
        AppUser sa = users.findById(username.trim()).orElseThrow(() -> bad("Please choose the " + location + " State Admin."));
        if (sa.getRole() != Role.STATE_ADMIN) throw bad("Please choose the " + location + " State Admin.");
        if (!sa.isActive()) throw bad("The " + location + " State Admin is not active.");
        return sa;
    }

    private AppUser findInvestigator(Actor a, String username) {
        AppUser inv = users.findById(username == null ? "" : username).orElseThrow(() -> bad("Please choose an investigator."));
        if (inv.getRole() != Role.INVESTIGATOR || !a.canSee(inv.getOwnerAdmin())) throw bad("Please choose an investigator.");
        if (!inv.isActive() || inv.getApprovalStatus() != UserStatus.APPROVED) throw bad("That investigator is not active / approved yet.");
        return inv;
    }

    private void applyAssignment(CaseRecord c, CaseReq r) {
        String tmc = req(r.tmcNumber(), "TMCID", 60);
        if (!TMC.matcher(tmc).matches()) throw bad("TMCID may only contain letters, digits, space and . _ / -");
        c.setTmcNumber(tmc);
        c.setPatientName(req(r.patientName(), "Patient name", 150));
        c.setDistrict(req(r.district(), "Patient district", 80));
        c.setPatientState(req(r.patientState(), "Patient state", 80));
        c.setVillage(req(r.village(), "Patient village", 120));
        c.setHospitalDistrict(req(r.hospitalDistrict(), "Hospital district", 80));
        c.setHospitalState(req(r.hospitalState(), "Hospital state", 80));
        c.setDoa(date(req(r.doa(), "Date of admission", 12), "Date of admission"));
        String dod = opt(r.dod(), 12);
        c.setDod(dod == null ? null : date(dod, "Date of discharge"));
        if (c.getDod() != null && c.getDod().isBefore(c.getDoa())) throw bad("Date of discharge cannot be before date of admission.");
        c.setHospitalName(req(r.hospitalName(), "Hospital name", 200));
        c.setInsuranceCompany(req(r.insuranceCompany(), "Insurance company", 120));
        ClaimType t;
        try { t = ClaimType.valueOf(r.claimType() == null ? "" : r.claimType()); }
        catch (IllegalArgumentException e) { throw bad("Please choose the type of insurance claim."); }
        c.setClaimType(t);
        if (t.partsFor(r.billSource()).contains(PartType.POLICE_STATION)) {
            c.setPoliceStationState(req(r.policeStationState(), "Police station state", 80));
            c.setPoliceStationDistrict(req(r.policeStationDistrict(), "Police station district", 80));
        } else {
            c.setPoliceStationState(null);
            c.setPoliceStationDistrict(null);
        }
        if (t.needsBillSource()) {
            String bs = r.billSource();
            if (!"HOSPITAL".equals(bs) && !"MEDICAL_SHOP".equals(bs)) throw bad("Choose hospital or local medical shop for bill verification.");
            c.setBillSource(bs);
        } else {
            c.setBillSource(null);
        }
    }

    /** Make the case's parts match what its claim type requires, keeping answers already given. */
    private void syncParts(CaseRecord c) {
        List<PartType> want = c.getClaimType().partsFor(c.getBillSource());
        c.getParts().removeIf(p -> !want.contains(p.getType()));
        Set<PartType> have = c.getParts().stream().map(CasePart::getType).collect(Collectors.toSet());
        for (PartType t : want) if (!have.contains(t)) c.getParts().add(new CasePart(c, t));
    }

    /**
     * Give every part an investigator from the part's State Admin and location state. Order of preference:
     * the explicit choice for that part, then the part's current investigator, then the single default investigator.
     */
    private void assignParts(Actor a, CaseRecord c, CaseReq r, boolean isNew) {
        Map<PartType, String> chosen = new EnumMap<>(PartType.class);
        if (r.partAssignments() != null) {
            for (PartAssign pa : r.partAssignments()) {
                if (pa == null || !notBlank(pa.investigatorUsername())) continue;
                try { chosen.put(PartType.valueOf(pa.type()), pa.investigatorUsername().trim()); }
                catch (Exception e) { throw bad("Unknown part."); }
            }
        }
        Map<String, AppUser> cache = new HashMap<>();
        for (CasePart p : c.getParts()) {
            if (!canManagePart(a, p, c)) continue;
            if (p.getOwnerAdmin() == null) p.setOwnerAdmin(partOwner(p, c));
            String current = isNew ? null : partInv(p, c);
            String want = chosen.get(p.getType());
            if (!notBlank(want)) want = current;
            if (!notBlank(want)) want = notBlank(r.investigatorUsername()) ? r.investigatorUsername().trim() : null;
            if (!notBlank(want)) throw bad("Please choose an investigator for: " + p.getType().label() + ".");
            if (want.equals(current)) {
                if (p.getInvestigatorUsername() == null) {       // older rows: store the owner on the part itself
                    p.setInvestigatorUsername(current);
                    p.setInvestigatorName(partInvName(p, c));
                }
                continue;
            }
            final String uname = want;
            AppUser inv = cache.computeIfAbsent(uname, u -> findInvestigator(a, u));
            String partOwner = partOwner(p, c);
            if (!Objects.equals(inv.getOwnerAdmin(), partOwner))
                throw bad(inv.getFullName() + " belongs to a different State Admin than this part.");
            String expectedState = partState(c, p.getType());
            if (normalizeState(expectedState).isEmpty()
                    || !normalizeState(inv.getState()).equals(normalizeState(expectedState)))
                throw bad("Choose an investigator from " + (expectedState == null ? "the required" : expectedState) + " state for "
                        + p.getType().label() + ".");
            if (current != null) {
                p.setTransferHistory(trim1000(join(p.getTransferHistory(), "Reassigned from " + partInvName(p, c) + " to "
                        + inv.getFullName() + " on " + LocalDate.now(IST) + " by " + a.username())));
            }
            moveTo(p, inv);
            p.clearTransfer();
        }
        refreshPrimary(c);
    }

    private static String partState(CaseRecord c, PartType type) {
        return switch (type) {
            case PATIENT -> c.getPatientState();
            case POLICE_STATION -> c.getPoliceStationState();
            default -> c.getHospitalState();
        };
    }

    private static String normalizeState(String state) {
        return state == null ? "" : state.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Hand a part to an investigator and wipe the previous person's work on it. */
    private static void moveTo(CasePart p, AppUser inv) {
        p.setInvestigatorUsername(inv.getUsername());
        p.setInvestigatorName(inv.getFullName());
        p.resetWork();
    }

    /** The case's "primary" investigator = whoever has its first part (kept for sorting / older screens). */
    private void refreshPrimary(CaseRecord c) {
        if (c.getParts().isEmpty()) return;
        CasePart first = c.getParts().get(0);
        String u = partInv(first, c);
        c.setInvestigatorUsername(u == null ? NO_INVESTIGATOR : u);
        c.setInvestigatorName(partInvName(first, c));
    }


    private static String partInv(CasePart p, CaseRecord c) {
        String u = p.getInvestigatorUsername() != null ? p.getInvestigatorUsername() : c.getInvestigatorUsername();
        return (u == null || u.isBlank()) ? null : u;
    }
    private static String partInvName(CasePart p, CaseRecord c) {
        if (p.getInvestigatorName() != null) return p.getInvestigatorName();
        return p.getInvestigatorUsername() != null ? p.getInvestigatorUsername() : c.getInvestigatorName();
    }
    private static boolean involves(CaseRecord c, String username) {
        if (username == null) return false;
        if (c.getParts().isEmpty()) return username.equals(c.getInvestigatorUsername());
        for (CasePart p : c.getParts()) if (username.equals(partInv(p, c))) return true;
        return false;
    }
    private static String investigatorNames(CaseRecord c) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (CasePart p : c.getParts()) { String n = partInvName(p, c); if (n != null) names.add(n); }
        if (names.isEmpty() && c.getInvestigatorName() != null) names.add(c.getInvestigatorName());
        return String.join(", ", names);
    }
    private static String investigatorNames(CaseRecord c, Actor viewer) {
        if (viewer.role() == Role.MAIN_ADMIN) return investigatorNames(c);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (CasePart p : c.getParts()) {
            boolean visible = viewer.role() == Role.INVESTIGATOR
                    ? viewer.username().equals(partInv(p, c)) : ownsPart(viewer, p, c);
            if (visible && partInvName(p, c) != null) names.add(partInvName(p, c));
        }
        return String.join(", ", names);
    }
    /** True while at least one part has no investigator yet (State Admin still has to assign). */
    private static boolean awaitingAssignment(CaseRecord c) {
        if (c.getParts().isEmpty()) return false;
        for (CasePart p : c.getParts()) if (partInv(p, c) == null) return true;
        return false;
    }
    private static boolean allSubmitted(CaseRecord c) {
        if (c.getParts().isEmpty()) return false;
        for (CasePart p : c.getParts()) if (!p.isSubmitted()) return false;
        return true;
    }
    private static boolean allReviewed(CaseRecord c) {
        return !c.getParts().isEmpty() && c.getParts().stream().allMatch(CasePart::isReviewed);
    }
    private static boolean partFlagged(CasePart p) {
        return "N".equals(p.getAnswer()) || p.isViaOther() || p.getRemarks() != null;
    }
    private static boolean teamReadyForReview(Actor viewer, CaseRecord c) {
        return (viewer.role() == Role.STATE_ADMIN || viewer.role() == Role.OFFICE_ASSISTANT)
                && c.getParts().stream().anyMatch(p -> ownsPart(viewer, p, c))
                && c.getParts().stream().filter(p -> ownsPart(viewer, p, c)).allMatch(CasePart::isSubmitted)
                && c.getParts().stream().filter(p -> ownsPart(viewer, p, c)).anyMatch(p -> !p.isReviewed());
    }
    private static boolean computeFlagged(CaseRecord c) {
        for (CasePart p : c.getParts()) {
            if ("N".equals(p.getAnswer()) || p.isViaOther() || p.getRemarks() != null) return true;
        }
        return false;
    }
    /** One remarks text for the case: plain when one investigator, "Name: text" lines when several. */
    private static String combinedRemarks(CaseRecord c) {
        LinkedHashMap<String, String> by = new LinkedHashMap<>();
        for (CasePart p : c.getParts()) {
            if (p.getRemarks() == null) continue;
            by.putIfAbsent(String.valueOf(partInv(p, c)), p.getRemarks());
        }
        if (by.isEmpty()) return null;
        Set<String> distinctInvs = new HashSet<>();
        for (CasePart p : c.getParts()) distinctInvs.add(String.valueOf(partInv(p, c)));
        if (distinctInvs.size() <= 1) return by.values().iterator().next();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : by.entrySet()) {
            String name = c.getParts().stream().filter(p -> e.getKey().equals(String.valueOf(partInv(p, c))))
                    .map(p -> partInvName(p, c)).findFirst().orElse(e.getKey());
            if (sb.length() > 0) sb.append('\n');
            sb.append(name).append(": ").append(e.getValue());
        }
        String out = sb.toString();
        return out.length() > 2000 ? out.substring(0, 2000) : out;
    }
    private static CasePart partOf(CaseRecord c, String typeName) {
        PartType t;
        try { t = PartType.valueOf(typeName == null ? "" : typeName); } catch (Exception e) { throw bad("Unknown part."); }
        for (CasePart p : c.getParts()) if (p.getType() == t) return p;
        throw bad("That part does not belong to this case.");
    }
    private static String join(String old, String add) { return (old == null || old.isBlank()) ? add : old + "\n" + add; }
    private static String trim1000(String s) { return s.length() <= 1000 ? s : s.substring(s.length() - 1000); }

    private boolean matchesView(CaseRecord c, String v, Actor viewer) {
        if (v == null || v.isBlank() || v.equals("all")) return true;
        return switch (v) {
            case "open" -> c.getStatus() == CaseStatus.ASSIGNED;
            case "unassigned" -> viewer.role() == Role.MAIN_ADMIN ? awaitingAssignment(c)
                    : c.getParts().stream().anyMatch(p -> visibleTo(viewer, p, c) && partInv(p, c) == null);
            case "review" -> c.getStatus() == CaseStatus.SUBMITTED
                    && (!canSeeCaseWideDetails(viewer, c) || !c.isFlagged());
            case "remarks" -> canSeeCaseWideDetails(viewer, c)
                    && c.getStatus() == CaseStatus.SUBMITTED && c.isFlagged();
            case "sentback" -> c.getStatus() == CaseStatus.SENT_BACK;
            case "completed" -> c.getStatus() == CaseStatus.COMPLETED;
            case "editreq" -> editRequestVisibleTo(viewer, c);
            case "transfer" -> c.getParts().stream().anyMatch(x -> visibleTo(viewer, x, c) && x.getTransferStatus() != null);
            default -> true;
        };
    }

    private static boolean visibleTo(Actor viewer, CasePart p, CaseRecord c) {
        return viewer.role() == Role.MAIN_ADMIN || (viewer.role() == Role.INVESTIGATOR
                ? viewer.username().equals(partInv(p, c)) : ownsPart(viewer, p, c));
    }

    private static boolean canSeeCaseWideDetails(Actor viewer, CaseRecord c) {
        return viewer.role() == Role.MAIN_ADMIN
                || (viewer.role() != Role.INVESTIGATOR
                    && c.getParts().stream().map(p -> partOwner(p, c)).distinct().count() <= 1);
    }

    private static boolean editRequestVisibleTo(Actor viewer, CaseRecord c) {
        return c.isEditRequested() && (viewer.role() == Role.MAIN_ADMIN
                || c.getParts().stream().anyMatch(p -> visibleTo(viewer, p, c)
                    && (c.getEditRequestedBy() == null || c.getEditRequestedBy().equals(partInv(p, c)))));
    }

    private Map<String, Object> view(CaseRecord c, Actor viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        int done = 0;
        for (CasePart p : c.getParts()) if (p.isSubmitted()) done++;
        int total = c.getParts().size();
        m.put("id", c.getId());
        m.put("tmcNumber", c.getTmcNumber());
        m.put("patientName", c.getPatientName());
        m.put("district", c.getDistrict());
        m.put("patientDistrict", c.getDistrict());
        m.put("patientState", c.getPatientState());
        m.put("hospitalDistrict", c.getHospitalDistrict());
        m.put("hospitalState", c.getHospitalState());
        m.put("policeStationState", c.getPoliceStationState());
        m.put("policeStationDistrict", c.getPoliceStationDistrict());
        m.put("village", c.getVillage());
        m.put("doa", str(c.getDoa()));
        m.put("dod", str(c.getDod()));
        m.put("hospitalName", c.getHospitalName());
        m.put("insuranceCompany", c.getInsuranceCompany());
        m.put("claimType", c.getClaimType().name());
        m.put("claimTypeLabel", c.getClaimType().label());
        m.put("billSource", c.getBillSource());
        boolean mainAdmin = viewer != null && viewer.role() == Role.MAIN_ADMIN;
        boolean investigator = viewer != null && viewer.role() == Role.INVESTIGATOR;
        boolean crossAdminCase = c.getParts().stream().map(p -> partOwner(p, c)).distinct().count() > 1;
        boolean hasOwnedParts = viewer != null && c.getParts().stream().anyMatch(p -> ownsPart(viewer, p, c));
        boolean privateProgress = investigator || (viewer.role().isStaff() && (crossAdminCase || !hasOwnedParts));
        m.put("investigatorUsername", privateProgress ? null : c.getInvestigatorUsername());
        m.put("investigatorName", privateProgress ? null : investigatorNames(c));
        m.put("ownerAdmin", mainAdmin ? c.getOwnerAdmin() : null);
        m.put("canManageCase", mainAdmin || (viewer.role().isStaff() && hasOwnedParts));
        m.put("createdBy", mainAdmin ? c.getCreatedBy() : null);
        m.put("status", c.getStatus().name());
        boolean awaiting = viewer != null && (viewer.role() == Role.STATE_ADMIN || viewer.role() == Role.OFFICE_ASSISTANT) && crossAdminCase
                ? c.getParts().stream().anyMatch(p -> ownsPart(viewer, p, c) && partInv(p, c) == null)
                : awaitingAssignment(c);
        m.put("awaitingAssignment", awaiting);
        String label = c.getStatus().label();
        if (c.getStatus() == CaseStatus.ASSIGNED && awaiting) label = "Awaiting investigator assignment";
        else if (c.getStatus() == CaseStatus.ASSIGNED && done > 0) label = "In progress (" + done + "/" + total + " parts submitted)";
        if (c.getStatus() == CaseStatus.COMPLETED) label = "Completed — sent to Main Admin";
        if (!mainAdmin && (viewer.role() == Role.STATE_ADMIN || viewer.role() == Role.OFFICE_ASSISTANT)) {
            List<CasePart> teamParts = c.getParts().stream().filter(p -> ownsPart(viewer, p, c)).toList();
            if (!teamParts.isEmpty() && teamParts.stream().allMatch(CasePart::isReviewed)
                    && c.getStatus() != CaseStatus.COMPLETED) label = "Your team's parts reviewed";
            else if (teamReadyForReview(viewer, c)) label = "Ready for your review";
        }
        m.put("statusLabel", investigator ? (done == total ? "All parts completed" : "Not completed") : label);
        m.put("partsSubmitted", privateProgress ? null : done);
        m.put("partsTotal", privateProgress ? null : total);
        m.put("flagged", privateProgress ? false : c.isFlagged());
        List<CasePart> reviewParts = mainAdmin ? c.getParts()
                : viewer.role() == Role.STATE_ADMIN || viewer.role() == Role.OFFICE_ASSISTANT
                    ? c.getParts().stream().filter(p -> ownsPart(viewer, p, c)).toList()
                    : List.of();
        m.put("reviewFlagged", reviewParts.stream().anyMatch(CaseService::partFlagged));
        m.put("remarks", privateProgress ? null : c.getRemarks());
        m.put("submittedAt", str(c.getSubmittedAt()));
        m.put("claimNo", c.getClaimNo());
        m.put("policyNo", c.getPolicyNo());
        m.put("patientAge", c.getPatientAge());
        m.put("ipNo", c.getIpNo());
        m.put("sentBackReason", privateProgress ? null : c.getSentBackReason());
        m.put("sentBackBy", privateProgress ? null : c.getSentBackBy());
        m.put("sentBackAt", str(c.getSentBackAt()));
        m.put("reviewNote", privateProgress ? null : c.getReviewNote());
        m.put("reviewedBy", privateProgress ? null : c.getReviewedBy());
        m.put("reviewedAt", privateProgress ? null : str(c.getReviewedAt()));
        boolean editVisible = c.isEditRequested() && (mainAdmin
                || c.getParts().stream().anyMatch(p -> visibleTo(viewer, p, c)
                    && (c.getEditRequestedBy() == null || c.getEditRequestedBy().equals(partInv(p, c)))));
        m.put("editRequested", editVisible);
        m.put("editRequestReason", editVisible ? c.getEditRequestReason() : null);
        m.put("canComplete", mainAdmin
                ? c.getStatus() == CaseStatus.SUBMITTED && allSubmitted(c)
                    && (!crossAdminCase || allReviewed(c))
                : teamReadyForReview(viewer, c));
        m.put("canRequestEdit", investigator && (c.getStatus() == CaseStatus.COMPLETED
                || c.getParts().stream().anyMatch(p -> viewer.username().equals(partInv(p, c)) && p.isReviewed())));
        m.put("createdAt", str(c.getCreatedAt()));
        m.put("newForCurrentUser", c.isNewCaseNotification() && !c.getViewedBy().contains(viewer.username()));
        m.put("month", YearMonth.from(c.getCreatedAt().atZone(IST)).toString());
        boolean anyTransfer = false;
        List<Map<String, Object>> parts = new ArrayList<>();
        for (CasePart p : c.getParts()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            boolean visible = mainAdmin || (investigator ? viewer.username().equals(partInv(p, c)) : ownsPart(viewer, p, c));
            pm.put("type", p.getType().name());
            pm.put("label", p.getType().label());
            pm.put("verification", p.getType().verification());
            pm.put("assigned", visible ? partInv(p, c) != null : true);
            pm.put("visible", visible);
            if (mainAdmin) pm.put("stateAdminUsername", partOwner(p, c));
            pm.put("investigatorUsername", visible ? partInv(p, c) : null);
            pm.put("investigatorName", visible ? partInvName(p, c) : null);
            pm.put("mine", viewer != null && viewer.username().equals(partInv(p, c)));
            pm.put("submitted", p.isSubmitted());
            pm.put("reviewed", visible && p.isReviewed());
            pm.put("reviewNote", visible ? p.getReviewNote() : null);
            pm.put("answer", visible ? p.getAnswer() : null);
            pm.put("reason", visible ? p.getReason() : null);
            pm.put("viaOther", visible && p.isViaOther());
            pm.put("note", visible ? p.getNote() : null);
            pm.put("remarks", visible ? p.getRemarks() : null);
            pm.put("mrdBillAvailable", visible ? p.getMrdBillAvailable() : null);
            pm.put("mrdBillAmount", visible ? p.getMrdBillAmount() : null);
            pm.put("transferStatus", visible ? p.getTransferStatus() : null);
            pm.put("transferReason", visible ? p.getTransferReason() : null);
            pm.put("transferTarget", visible ? p.getTransferTarget() : null);
            pm.put("transferRequestedAt", visible ? str(p.getTransferRequestedAt()) : null);
            pm.put("transferDecisionNote", visible ? p.getTransferDecisionNote() : null);
            pm.put("transferHistory", visible ? p.getTransferHistory() : null);
            if (visible && p.getTransferStatus() != null) anyTransfer = true;
            parts.add(pm);
        }
        m.put("transferPending", anyTransfer);
        m.put("parts", parts);
        return m;
    }

    private void requireStaff(Actor a) { if (!a.role().isStaff()) throw forbidden(); }

    private static LocalDate date(String s, String label) {
        try { return LocalDate.parse(s); } catch (DateTimeParseException e) { throw bad(label + " is not a valid date."); }
    }
    private static String str(Object o) { return o == null ? null : o.toString(); }
    private static String low(String s) { return s == null ? "" : s.toLowerCase(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }
    private static String req(String s, String label, int max) {
        String v = opt(s, max);
        if (v == null) throw bad(label + " is required.");
        return v;
    }
    private static String opt(String s, int max) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return null;
        if (v.length() > max) throw bad("A value is too long (max " + max + " characters).");
        return v;
    }
    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission for this action."); }
}
