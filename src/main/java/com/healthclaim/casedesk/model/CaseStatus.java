package com.healthclaim.casedesk.model;

public enum CaseStatus {
    ASSIGNED("Open"), SUBMITTED("Submitted"), SENT_BACK("Sent back"), COMPLETED("Completed");
    private final String label;
    CaseStatus(String label) { this.label = label; }
    public String label() { return label; }
}
