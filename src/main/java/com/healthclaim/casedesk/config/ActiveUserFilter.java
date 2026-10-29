package com.healthclaim.casedesk.config;

import com.healthclaim.casedesk.model.AppUser;
import com.healthclaim.casedesk.model.UserStatus;
import com.healthclaim.casedesk.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * On every request: make sure the logged-in account still exists, is active and approved
 * (so deactivating someone kicks them out immediately), and force a password change if required.
 */
public class ActiveUserFilter extends OncePerRequestFilter {

    private final UserRepository users;

    public ActiveUserFilter(UserRepository users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication au = SecurityContextHolder.getContext().getAuthentication();
        if (au != null && au.getPrincipal() instanceof Actor a) {
            AppUser u = users.findById(a.username()).orElse(null);
            if (u == null || !u.isActive() || u.getApprovalStatus() != UserStatus.APPROVED || u.getRole() != a.role()) {
                HttpSession s = req.getSession(false);
                if (s != null) s.invalidate();
                SecurityContextHolder.clearContext();
                json(res, 401, "Session ended. Please sign in again.");
                return;
            }
            if (u.isMustChangePassword()) {
                String p = req.getRequestURI();
                if (!(p.equals("/api/auth/change-password") || p.equals("/api/auth/logout") || p.equals("/api/auth/me"))) {
                    json(res, 403, "PASSWORD_CHANGE_REQUIRED");
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }

    private void json(HttpServletResponse res, int status, String msg) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"message\":\"" + msg + "\"}");
    }
}
