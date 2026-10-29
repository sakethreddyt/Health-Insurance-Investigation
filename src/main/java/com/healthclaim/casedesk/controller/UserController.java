package com.healthclaim.casedesk.controller;

import com.healthclaim.casedesk.config.Actor;
import com.healthclaim.casedesk.service.Dto.NoteReq;
import com.healthclaim.casedesk.service.Dto.PasswordReq;
import com.healthclaim.casedesk.service.Dto.UserReq;
import com.healthclaim.casedesk.service.UserService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private static final String STAFF = "hasAnyRole('MAIN_ADMIN','STATE_ADMIN','OFFICE_ASSISTANT')";
    private static final String ADMIN = "hasAnyRole('MAIN_ADMIN','STATE_ADMIN')";

    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @GetMapping
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> list(@AuthenticationPrincipal Actor a, @RequestParam(required = false) String role) {
        return users.list(a, role);
    }

    @GetMapping("/state-admins")
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> activeStateAdmins(@AuthenticationPrincipal Actor a) {
        return users.activeStateAdmins(a);
    }

    @GetMapping("/{username}")
    @PreAuthorize(STAFF)
    public Map<String, Object> get(@AuthenticationPrincipal Actor a, @PathVariable String username) {
        return users.get(a, username);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF)
    public Map<String, Object> create(@AuthenticationPrincipal Actor a,
                                      @RequestPart("data") UserReq data,
                                      @RequestPart(value = "photo", required = false) MultipartFile photo,
                                      @RequestPart(value = "aadharFile", required = false) MultipartFile aadharFile) {
        return users.create(a, data, photo, aadharFile);
    }

    @PutMapping(value = "/{username}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF)
    public Map<String, Object> update(@AuthenticationPrincipal Actor a, @PathVariable String username,
                                      @RequestPart("data") UserReq data,
                                      @RequestPart(value = "photo", required = false) MultipartFile photo,
                                      @RequestPart(value = "aadharFile", required = false) MultipartFile aadharFile) {
        return users.update(a, username, data, photo, aadharFile);
    }

    @PostMapping("/{username}/approve")
    @PreAuthorize(ADMIN)
    public Map<String, Object> approve(@AuthenticationPrincipal Actor a, @PathVariable String username) {
        return users.approve(a, username);
    }

    @PostMapping("/{username}/send-back")
    @PreAuthorize(ADMIN)
    public Map<String, Object> sendBack(@AuthenticationPrincipal Actor a, @PathVariable String username, @RequestBody NoteReq body) {
        return users.sendBack(a, username, body.note());
    }

    @PostMapping("/{username}/active/{value}")
    @PreAuthorize(ADMIN)
    public Map<String, Object> active(@AuthenticationPrincipal Actor a, @PathVariable String username, @PathVariable boolean value) {
        return users.setActive(a, username, value);
    }

    @PutMapping("/{username}/password")
    @PreAuthorize(ADMIN)
    public Map<String, String> reset(@AuthenticationPrincipal Actor a, @PathVariable String username, @RequestBody PasswordReq body) {
        users.resetPassword(a, username, body.newPassword());
        return Map.of("status", "ok");
    }

    /** Admin-only. Each call is audit-logged. */
    @GetMapping("/{username}/password")
    @PreAuthorize(ADMIN)
    public ResponseEntity<Map<String, String>> reveal(@AuthenticationPrincipal Actor a, @PathVariable String username) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("password", users.revealPassword(a, username)));
    }

    @GetMapping("/{username}/photo")
    public ResponseEntity<byte[]> photo(@AuthenticationPrincipal Actor a, @PathVariable String username) {
        return serve(users.file(a, username, true));
    }

    @GetMapping("/{username}/aadhar-file")
    public ResponseEntity<byte[]> aadhar(@AuthenticationPrincipal Actor a, @PathVariable String username) {
        return serve(users.file(a, username, false));
    }

    private ResponseEntity<byte[]> serve(UserService.FileData f) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + f.name() + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(f.bytes());
    }
}
