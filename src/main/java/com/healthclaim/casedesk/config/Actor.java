package com.healthclaim.casedesk.config;

import com.healthclaim.casedesk.model.Role;
import java.io.Serializable;

/**
 * The logged-in user as stored in the security context.
 * owner = the "tenant": which state-admin's data this person works inside.
 * (Main admin sees every tenant.)
 */
public record Actor(String username, Role role, String owner) implements Serializable {
    public boolean canSee(String ownerAdmin) {
        return role == Role.MAIN_ADMIN || (owner != null && owner.equals(ownerAdmin));
    }
}
