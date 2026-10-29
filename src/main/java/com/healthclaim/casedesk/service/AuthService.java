package com.healthclaim.casedesk.service;

import com.healthclaim.casedesk.model.AppUser;
import com.healthclaim.casedesk.model.UserStatus;
import com.healthclaim.casedesk.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
@Transactional
public class AuthService {

    public record LoginResult(AppUser user, String error) {}

    private static final int MAX_FAILS = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);
    private static final String BAD = "Invalid username or password. After 5 wrong attempts the account is locked for 15 minutes.";

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuditService audit;
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder encoder, AuditService audit) {
        this.users = users; this.encoder = encoder; this.audit = audit;
        this.dummyHash = encoder.encode("not-a-real-password");
    }

    public LoginResult authenticate(String username, String password) {
        String un = username == null ? "" : username.trim();
        String pw = password == null ? "" : password;
        AppUser u = un.isEmpty() ? null : users.findById(un).orElse(null);
        if (u == null) {                       // same work as a real check, so timing doesn't reveal valid usernames
            encoder.matches(pw, dummyHash);
            audit.log(un, "LOGIN_FAIL", "-", "unknown user");
            return new LoginResult(null, BAD);
        }
        if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now())) {
            audit.log(un, "LOGIN_BLOCKED", un, "account locked");
            return new LoginResult(null, BAD);
        }
        if (!encoder.matches(pw, u.getPasswordHash())) {
            int n = u.getFailedAttempts() + 1;
            if (n >= MAX_FAILS) {
                u.setLockedUntil(Instant.now().plus(LOCK));
                u.setFailedAttempts(0);
                audit.log(un, "ACCOUNT_LOCKED", un, "too many failed logins");
            } else {
                u.setFailedAttempts(n);
            }
            users.save(u);
            audit.log(un, "LOGIN_FAIL", un, "wrong password");
            return new LoginResult(null, BAD);
        }
        // password was right — now say precisely why access may still be refused
        if (u.getApprovalStatus() != UserStatus.APPROVED) {
            audit.log(un, "LOGIN_REFUSED", un, "not approved yet");
            return new LoginResult(null, "Your account is waiting for admin approval.");
        }
        if (!u.isActive()) {
            audit.log(un, "LOGIN_REFUSED", un, "deactivated");
            return new LoginResult(null, "Your account has been deactivated. Contact your administrator.");
        }
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        users.save(u);
        audit.log(un, "LOGIN_OK", un, u.getRole().name());
        return new LoginResult(u, null);
    }
}
