package com.healthclaim.casedesk.controller;

import com.healthclaim.casedesk.config.Actor;
import com.healthclaim.casedesk.config.LoginThrottle;
import com.healthclaim.casedesk.model.AppUser;
import com.healthclaim.casedesk.service.AuthService;
import com.healthclaim.casedesk.service.Dto.ChangePasswordReq;
import com.healthclaim.casedesk.service.Dto.LoginReq;
import com.healthclaim.casedesk.service.Dto.ProfileReq;
import com.healthclaim.casedesk.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final UserService users;
    private final LoginThrottle throttle;
    private final SecurityContextRepository contextRepo;

    public AuthController(AuthService auth, UserService users, LoginThrottle throttle, SecurityContextRepository contextRepo) {
        this.auth = auth; this.users = users; this.throttle = throttle; this.contextRepo = contextRepo;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@org.springframework.security.core.annotation.AuthenticationPrincipal Object principal) {
        if (principal instanceof Actor a) return users.me(a);
        return Map.of("authenticated", false);
    }

    @GetMapping("/profile")
    public Map<String, Object> profile(@org.springframework.security.core.annotation.AuthenticationPrincipal Actor a) {
        return users.profile(a);
    }

    @PutMapping(value = "/profile", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> updateProfile(@org.springframework.security.core.annotation.AuthenticationPrincipal Actor a,
                                             @RequestPart("data") ProfileReq data,
                                             @RequestPart(value = "photo", required = false) MultipartFile photo) {
        return users.updateProfile(a, data, photo);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginReq req, HttpServletRequest request, HttpServletResponse response) {
        if (!throttle.allow(request.getRemoteAddr()))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please wait a few minutes.");
        AuthService.LoginResult res = auth.authenticate(req.username(), req.password());
        if (res.user() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, res.error());
        AppUser u = res.user();

        if (request.getSession(false) != null) request.changeSessionId();   // new session id on login (anti-fixation)
        else request.getSession(true);

        Actor actor = new Actor(u.getUsername(), u.getRole(), u.getOwnerAdmin());
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()));
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(actor, null, authorities);
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(token);
        SecurityContextHolder.setContext(ctx);
        contextRepo.saveContext(ctx, request, response);
        return users.me(actor);
    }

    @PostMapping("/logout")
    public Map<String, String> logout(HttpServletRequest request) {
        HttpSession s = request.getSession(false);
        if (s != null) s.invalidate();
        SecurityContextHolder.clearContext();
        return Map.of("status", "ok");
    }

    @PostMapping("/change-password")
    public Map<String, String> changePassword(@org.springframework.security.core.annotation.AuthenticationPrincipal Actor a,
                                              @RequestBody ChangePasswordReq req) {
        users.changeOwnPassword(a, req.currentPassword(), req.newPassword());
        return Map.of("status", "ok");
    }
}
