package com.healthclaim.casedesk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Forces the XSRF-TOKEN cookie to be written so the single-page frontend can echo it in a header. */
public class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) req.getAttribute(CsrfToken.class.getName());
        if (token != null) token.getToken();
        chain.doFilter(req, res);
    }
}
