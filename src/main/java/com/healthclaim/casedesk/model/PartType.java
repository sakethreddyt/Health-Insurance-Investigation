package com.healthclaim.casedesk.model;

/** One thing an investigator must collect / verify for a case. */
public enum PartType {
    HOSPITAL("Hospital part", false),
    PATIENT("Patient part", false),
    MEDICAL_SHOP("Local medical shop", false),
    NOMINEE("Nominee verification", true),
    WITNESS("Witness verification", true),
    POLICE_STATION("Police station verification", true),
    LOCATION("Location verification", true);

    private final String label;
    private final boolean verification;
    PartType(String label, boolean verification) { this.label = label; this.verification = verification; }
    public String label() { return label; }
    /** true = "verification done?", false = "documents collected?" */
    public boolean verification() { return verification; }
}
