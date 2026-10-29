package com.healthclaim.casedesk.service;

import com.healthclaim.casedesk.config.Actor;
import com.healthclaim.casedesk.model.AppUser;
import com.healthclaim.casedesk.model.Role;
import com.healthclaim.casedesk.model.UserStatus;
import com.healthclaim.casedesk.repository.UserRepository;
import com.healthclaim.casedesk.service.Dto.ProfileReq;
import com.healthclaim.casedesk.service.Dto.UserReq;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Transactional
public class UserService {

    public record FileData(byte[] bytes, String contentType, String name) {}

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{4,40}$");
    private static final Pattern MOBILE = Pattern.compile("^[0-9+\\- ]{7,15}$");
    private static final long PHOTO_MAX = 2L * 1024 * 1024;
    private static final long AADHAR_MAX = 5L * 1024 * 1024;

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final FileStorageService files;
    private final AuditService audit;

    public UserService(UserRepository repo, PasswordEncoder encoder, FileStorageService files, AuditService audit) {
        this.repo = repo; this.encoder = encoder; this.files = files; this.audit = audit;
    }

    // ---------------------------------------------------------------- reading

    public Map<String, Object> me(Actor a) {
        AppUser u = repo.findById(a.username()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("authenticated", true);
        m.put("username", u.getUsername());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole().name());
        m.put("state", u.getState());
        m.put("ownerAdmin", u.getOwnerAdmin());
        String ownerUsername = u.getOwnerAdmin();
        AppUser owner = ownerUsername == null ? null : repo.findById(Objects.requireNonNull(ownerUsername)).orElse(null);
        m.put("ownerState", owner == null ? u.getState() : owner.getState());
        m.put("mustChangePassword", u.isMustChangePassword());
        m.put("hasPhoto", u.getPhotoFile() != null);
        return m;
    }

    public List<Map<String, Object>> activeStateAdmins(Actor a) {
        if (!a.role().isStaff()) throw forbidden();
        return repo.findByRoleAndActiveTrue(Role.STATE_ADMIN).stream()
                .sorted(Comparator.comparing(AppUser::getCreatedAt).reversed())
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("username", u.getUsername());
                    m.put("fullName", u.getFullName());
                    m.put("state", u.getState());
                    return m;
                }).toList();
    }

    public List<Map<String, Object>> list(Actor a, String role) {
        if (!a.role().isStaff()) throw forbidden();
        List<AppUser> all = a.role() == Role.MAIN_ADMIN ? repo.findAll() : repo.findByOwnerAdmin(a.owner());
        List<Map<String, Object>> out = new ArrayList<>();
        all.sort(Comparator.comparing(AppUser::getCreatedAt));
        Collections.reverse(all);
        for (AppUser u : all) {
            if (a.role() != Role.MAIN_ADMIN && (u.getRole() == Role.MAIN_ADMIN || u.getRole() == Role.STATE_ADMIN)) continue;
            if (role != null && !role.isBlank() && !u.getRole().name().equals(role)) continue;
            out.add(view(u, a));
        }
        return out;
    }

    public Map<String, Object> get(Actor a, String username) {
        return view(load(a, username), a);
    }

    public Map<String, Object> profile(Actor a) {
        String username = Objects.requireNonNull(a.username());
        AppUser u = repo.findById(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
        return view(u, a);
    }

    public FileData file(Actor a, String username, boolean photo) {
        AppUser u;
        if (a.username().equals(username)) u = repo.findById(username).orElseThrow(UserService::notFound);
        else if (a.role().isStaff()) u = load(a, username);
        else throw forbidden();
        String name = photo ? u.getPhotoFile() : u.getAadharFile();
        if (name == null) throw notFound();
        return new FileData(files.read(name), files.contentType(name), name);
    }

    // ---------------------------------------------------------------- creating / editing

    public Map<String, Object> create(Actor a, UserReq r, MultipartFile photo, MultipartFile aadharFile) {
        Role role = parseRole(r.role());
        boolean allowed = switch (a.role()) {
            case MAIN_ADMIN -> role != Role.MAIN_ADMIN;
            case STATE_ADMIN -> role == Role.OFFICE_ASSISTANT || role == Role.INVESTIGATOR;
            case OFFICE_ASSISTANT -> role == Role.INVESTIGATOR;
            default -> false;
        };
        if (!allowed) throw forbidden();

        String username = req(r.username(), "Username", 40);
        if (!USERNAME.matcher(username).matches())
            throw bad("Username must be 4-40 characters: letters, digits, dot, underscore or dash.");
        if (repo.existsById(username)) throw new ResponseStatusException(HttpStatus.CONFLICT, "That username is already taken.");
        checkPassword(r.password());

        AppUser u = new AppUser();
        u.setUsername(username);
        u.setRole(role);
        u.setFullName(req(r.fullName(), "Full name", 120));
        u.setMobile(mobile(r.mobile()));
        u.setState(req(r.state(), "State", 80));
        u.setDistrict(req(r.district(), "District", 80));
        u.setCreatedBy(a.username());
        u.setPasswordHash(encoder.encode(r.password()));

        // which admin's "tenant" does the new account belong to?
        if (role == Role.STATE_ADMIN) {
            if (u.getState() == null) throw bad("State is required for a state admin.");
            u.setOwnerAdmin(username);
        } else if (a.role() == Role.MAIN_ADMIN) {
            String owner = opt(r.ownerAdmin(), 80);
            if (owner == null) owner = a.username();
            AppUser o = repo.findById(owner).orElseThrow(() -> bad("Selected admin does not exist."));
            if (o.getRole() != Role.STATE_ADMIN && o.getRole() != Role.MAIN_ADMIN) throw bad("Selected owner is not an admin.");
            u.setOwnerAdmin(owner);
        } else {
            u.setOwnerAdmin(a.owner());
        }

        if (role == Role.INVESTIGATOR) {
            if (photo == null || photo.isEmpty()) throw bad("Passport-size photo is required.");
            if (aadharFile == null || aadharFile.isEmpty()) throw bad("Aadhaar card upload is required.");
            applyKyc(u, r);
            u.setApprovalStatus(a.role().isAdmin() ? UserStatus.APPROVED : UserStatus.PENDING_APPROVAL);
            u.setMustChangePassword(false);
            u.setPhotoFile(files.store(photo, false, PHOTO_MAX));
            try {
                u.setAadharFile(files.store(aadharFile, true, AADHAR_MAX));
            } catch (RuntimeException e) { files.delete(u.getPhotoFile()); throw e; }
        } else {
            u.setApprovalStatus(UserStatus.APPROVED);
            u.setMustChangePassword(true);   // nobody can read these passwords, so the person sets their own at first login
            if (photo != null && !photo.isEmpty()) u.setPhotoFile(files.store(photo, false, PHOTO_MAX));
        }
        repo.save(u);
        audit.log(a.username(), "USER_CREATE", username, role + (u.getApprovalStatus() == UserStatus.PENDING_APPROVAL ? " (sent for approval)" : ""));
        return view(u, a);
    }

    public Map<String, Object> update(Actor a, String username, UserReq r, MultipartFile photo, MultipartFile aadharFile) {
        AppUser u = load(a, username);
        requireCanManage(a, u);
        boolean officeResubmit = false;
        if (a.role() == Role.OFFICE_ASSISTANT) {
            if (u.getRole() != Role.INVESTIGATOR || u.getApprovalStatus() != UserStatus.CHANGES_REQUESTED) throw forbidden();
            officeResubmit = true;
        }
        u.setFullName(req(r.fullName(), "Full name", 120));
        u.setMobile(mobile(r.mobile()));
        u.setState(req(r.state(), "State", 80));
        u.setDistrict(req(r.district(), "District", 80));
        if (photo != null && !photo.isEmpty()) {
            String old = u.getPhotoFile();
            u.setPhotoFile(files.store(photo, false, PHOTO_MAX));
            files.delete(old);
        }
        if (u.getRole() == Role.INVESTIGATOR) {
            applyKyc(u, r);
            if (aadharFile != null && !aadharFile.isEmpty()) { String old = u.getAadharFile(); u.setAadharFile(files.store(aadharFile, true, AADHAR_MAX)); files.delete(old); }
        }
        if (officeResubmit) { u.setApprovalStatus(UserStatus.PENDING_APPROVAL); u.setReviewNote(null); }
        u.setUpdatedAt(Instant.now());
        repo.save(u);
        audit.log(a.username(), "USER_UPDATE", username, officeResubmit ? "re-submitted for approval" : "details edited");
        return view(u, a);
    }

    public Map<String, Object> updateProfile(Actor a, ProfileReq r, MultipartFile photo) {
        String username = Objects.requireNonNull(a.username());
        AppUser u = repo.findById(username).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in"));
        u.setFullName(req(r.fullName(), "Full name", 120));
        u.setMobile(mobile(r.mobile()));
        u.setState(req(r.state(), "State", 80));
        u.setDistrict(req(r.district(), "District", 80));
        if (u.getRole() == Role.INVESTIGATOR) {
            u.setAadhar(opt(r.aadhar(), 20));
            u.setPan(opt(r.pan(), 12));
            u.setDl(opt(r.dl(), 30));
            u.setBankName(opt(r.bankName(), 120));
            u.setBankAcc(opt(r.bankAcc(), 30));
            u.setIfsc(opt(r.ifsc(), 15));
        }
        if (photo != null && !photo.isEmpty()) {
            String old = u.getPhotoFile();
            u.setPhotoFile(files.store(photo, false, PHOTO_MAX));
            files.delete(old);
        }
        u.setUpdatedAt(Instant.now());
        repo.save(u);
        audit.log(a.username(), "PROFILE_UPDATE", a.username(), "own profile updated");
        return view(u, a);
    }

    // ---------------------------------------------------------------- approval workflow

    public Map<String, Object> approve(Actor a, String username) {
        requireAdmin(a);
        AppUser u = load(a, username);
        if (u.getRole() != Role.INVESTIGATOR || u.getApprovalStatus() != UserStatus.PENDING_APPROVAL)
            throw bad("This account is not waiting for approval.");
        u.setApprovalStatus(UserStatus.APPROVED);
        u.setReviewNote(null);
        repo.save(u);
        audit.log(a.username(), "USER_APPROVE", username, "investigator approved");
        return view(u, a);
    }

    public Map<String, Object> sendBack(Actor a, String username, String note) {
        requireAdmin(a);
        AppUser u = load(a, username);
        if (u.getRole() != Role.INVESTIGATOR || u.getApprovalStatus() != UserStatus.PENDING_APPROVAL)
            throw bad("This account is not waiting for approval.");
        u.setApprovalStatus(UserStatus.CHANGES_REQUESTED);
        u.setReviewNote(req(note, "Please say what needs to change", 1000));
        repo.save(u);
        audit.log(a.username(), "USER_SEND_BACK", username, u.getReviewNote());
        return view(u, a);
    }

    public Map<String, Object> setActive(Actor a, String username, boolean active) {
        requireAdmin(a);
        AppUser u = load(a, username);
        requireCanManage(a, u);
        if (u.getUsername().equals(a.username())) throw bad("You cannot deactivate your own account.");
        u.setActive(active);
        repo.save(u);
        audit.log(a.username(), active ? "USER_ACTIVATE" : "USER_DEACTIVATE", username, null);
        return view(u, a);
    }

    // ---------------------------------------------------------------- passwords

    /** Admin only: set a new password for an investigator / office assistant (/ state admin when main admin). */
    public void resetPassword(Actor a, String username, String newPassword) {
        requireAdmin(a);
        AppUser u = load(a, username);
        requireCanManage(a, u);
        checkPassword(newPassword);
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(true);
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        repo.save(u);
        audit.log(a.username(), "PASSWORD_RESET", username, null);
    }

    public void changeOwnPassword(Actor a, String current, String next) {
        AppUser u = repo.findById(a.username()).orElseThrow(UserService::notFound);
        if (current == null || !encoder.matches(current, u.getPasswordHash())) throw bad("Current password is incorrect.");
        checkPassword(next);
        if (encoder.matches(next, u.getPasswordHash())) throw bad("Choose a password different from the current one.");
        u.setPasswordHash(encoder.encode(next));
        u.setMustChangePassword(false);
        repo.save(u);
        audit.log(a.username(), "PASSWORD_CHANGED", a.username(), null);
    }

    // ---------------------------------------------------------------- helpers

    /** Loads a user the actor is allowed to see; otherwise 404 (we don't reveal that it exists). */
    AppUser load(Actor a, String username) {
        AppUser u = repo.findById(username).orElseThrow(UserService::notFound);
        if (!a.canSee(u.getOwnerAdmin())) throw notFound();
        if (a.role() != Role.MAIN_ADMIN && (u.getRole() == Role.MAIN_ADMIN || u.getRole() == Role.STATE_ADMIN)
                && !u.getUsername().equals(a.username())) throw notFound();
        return u;
    }

    private void requireAdmin(Actor a) { if (!a.role().isAdmin()) throw forbidden(); }

    private void requireCanManage(Actor a, AppUser target) {
        switch (a.role()) {
            case MAIN_ADMIN -> { }
            case STATE_ADMIN -> { if (target.getRole() != Role.OFFICE_ASSISTANT && target.getRole() != Role.INVESTIGATOR) throw forbidden(); }
            case OFFICE_ASSISTANT -> { if (target.getRole() != Role.INVESTIGATOR) throw forbidden(); }
            default -> throw forbidden();
        }
    }

    private Map<String, Object> view(AppUser u, Actor viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("fullName", u.getFullName());
        m.put("role", u.getRole().name());
        m.put("mobile", u.getMobile());
        m.put("state", u.getState());
        m.put("district", u.getDistrict());
        m.put("ownerAdmin", u.getOwnerAdmin());
        m.put("active", u.isActive());
        m.put("approvalStatus", u.getApprovalStatus().name());
        m.put("reviewNote", u.getReviewNote());
        m.put("createdBy", u.getCreatedBy());
        m.put("createdAt", u.getCreatedAt().toString());
        m.put("hasPhoto", u.getPhotoFile() != null);
        m.put("hasAadharFile", u.getAadharFile() != null);
        if (u.getRole() == Role.INVESTIGATOR && (viewer.role().isStaff() || viewer.username().equals(u.getUsername()))) {
            m.put("aadhar", u.getAadhar());
            m.put("pan", u.getPan());
            m.put("dl", u.getDl());
            m.put("bankName", u.getBankName());
            m.put("bankAcc", u.getBankAcc());
            m.put("ifsc", u.getIfsc());
        }
        return m;
    }

    private void applyKyc(AppUser u, UserReq r) {
        u.setAadhar(opt(r.aadhar(), 20));
        u.setPan(opt(r.pan(), 12));
        u.setDl(opt(r.dl(), 30));
        u.setBankName(opt(r.bankName(), 120));
        u.setBankAcc(opt(r.bankAcc(), 30));
        u.setIfsc(opt(r.ifsc(), 15));
    }

    private static Role parseRole(String s) {
        try { return Role.valueOf(s == null ? "" : s.trim()); }
        catch (IllegalArgumentException e) { throw bad("Unknown role."); }
    }

    private static void checkPassword(String p) {
        if (p == null || p.length() < 8 || p.length() > 100) throw bad("Password must be 8-100 characters.");
        boolean letter = false, digit = false;
        for (char c : p.toCharArray()) { if (Character.isLetter(c)) letter = true; if (Character.isDigit(c)) digit = true; }
        if (!letter || !digit) throw bad("Password must contain at least one letter and one digit.");
    }

    private static String mobile(String s) {
        String v = opt(s, 15);
        if (v != null && !MOBILE.matcher(v).matches()) throw bad("Mobile number looks invalid.");
        return v;
    }

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
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found."); }
}
