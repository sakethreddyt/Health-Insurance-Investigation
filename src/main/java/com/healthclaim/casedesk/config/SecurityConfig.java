package com.healthclaim.casedesk.config;

import com.healthclaim.casedesk.repository.UserRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    public SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, UserRepository users,
                                           SecurityContextRepository repo) throws Exception {
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);   // plain (non-deferred) token, the SPA pattern

        http
            .securityContext(sc -> sc.securityContextRepository(repo))
            .csrf(c -> c.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfHandler))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(a -> a
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                .requestMatchers("/", "/index.html", "/app.js", "/app.css", "/favicon.ico",
                                 "/india-states-districts.json",
                                 "/api/auth/login", "/api/auth/me").permitAll()
                .anyRequest().authenticated())      // everything else (incl. /docs/**, uploads) needs login
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .headers(h -> h
                .contentSecurityPolicy(c -> c.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; "
                    + "font-src https://fonts.gstatic.com; img-src 'self' data: blob:; object-src 'none'; "
                    + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'"))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .addFilterAfter(new ActiveUserFilter(users), CsrfCookieFilter.class);
        return http.build();
    }
}
