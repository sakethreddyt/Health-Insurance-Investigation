package com.healthclaim.casedesk.model;

public enum Role {
    MAIN_ADMIN, STATE_ADMIN, OFFICE_ASSISTANT, INVESTIGATOR;

    /** Main admin or state admin. */
    public boolean isAdmin() { return this == MAIN_ADMIN || this == STATE_ADMIN; }
    /** Anyone who works in the office (admins + office assistants). */
    public boolean isStaff() { return this != INVESTIGATOR; }
}
