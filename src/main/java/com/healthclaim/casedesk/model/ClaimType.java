package com.healthclaim.casedesk.model;

import java.util.List;

import static com.healthclaim.casedesk.model.PartType.*;

/**
 * Which parts must be handled for each type of claim.
 * EDIT THIS FILE to change the rules — the UI is driven from it.
 */
public enum ClaimType {
    CASHLESS("Cashless", List.of(HOSPITAL)),
    REIMBURSEMENT("Reimbursement", List.of(HOSPITAL, PATIENT)),
    PERSONAL_ACCIDENT("Personal Accident", List.of(NOMINEE, WITNESS, POLICE_STATION, HOSPITAL)),
    // for medical bill verification the admin chooses hospital OR medical shop when assigning
    MEDICAL_BILL_VERIFICATION("Medical Bill Verification", List.of()),
    REIMBURSEMENT_DNDM("Reimbursement DNDM", List.of(HOSPITAL)),
    DEATH("Death", List.of(HOSPITAL, NOMINEE, POLICE_STATION, LOCATION));

    private final String label;
    private final List<PartType> parts;
    ClaimType(String label, List<PartType> parts) { this.label = label; this.parts = parts; }
    public String label() { return label; }
    public List<PartType> fixedParts() { return parts; }
    public boolean needsBillSource() { return this == MEDICAL_BILL_VERIFICATION; }

    public List<PartType> partsFor(String billSource) {
        if (needsBillSource()) {
            return "MEDICAL_SHOP".equals(billSource) ? List.of(MEDICAL_SHOP) : List.of(HOSPITAL);
        }
        return parts;
    }
}
