package com.healthclaim.casedesk.config;

import com.healthclaim.casedesk.model.AppUser;
import com.healthclaim.casedesk.model.Role;
import com.healthclaim.casedesk.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/** Creates the first Main Admin. There is NO default "admin/admin" any more. */
@Component
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String envPassword;

    public BootstrapRunner(UserRepository users, PasswordEncoder encoder,
                           @Value("${app.bootstrap.admin-password:}") String envPassword) {
        this.users = users; this.encoder = encoder; this.envPassword = envPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByRole(Role.MAIN_ADMIN)) return;
        String pw = envPassword;
        boolean generated = false;
        if (pw == null || pw.isBlank()) { pw = randomPassword(); generated = true; }
        AppUser u = new AppUser();
        u.setUsername("mainadmin");
        u.setFullName("Main Administrator");
        u.setRole(Role.MAIN_ADMIN);
        u.setOwnerAdmin("mainadmin");
        u.setPasswordHash(encoder.encode(pw));
        u.setMustChangePassword(true);
        u.setCreatedBy("system");
        users.save(u);
        if (generated) {
            log.warn("==========================================================");
            log.warn(" First-run Main Admin created.  username: mainadmin");
            log.warn(" One-time password: {}", pw);
            log.warn(" You must change it at first login. This is shown only once.");
            log.warn("==========================================================");
        } else {
            log.info("Main Admin 'mainadmin' created from APP_BOOTSTRAP_ADMIN_PASSWORD (must change at first login).");
        }
    }

    private static String randomPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.append("7").toString();
    }
}
