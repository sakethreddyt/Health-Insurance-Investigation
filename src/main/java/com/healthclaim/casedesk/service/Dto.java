package com.healthclaim.casedesk.service;

import java.math.BigDecimal;
import java.util.List;

/** Request payloads. */
public final class Dto {
    private Dto() {}

    public record LoginReq(String username, String password) {}
    public record ChangePasswordReq(String currentPassword, String newPassword) {}
    public record PasswordReq(String newPassword) {}
    public record NoteReq(String note) {}
    public record ProfileReq(String fullName, String mobile, String state, String district,
                             String aadhar, String pan, String dl, String bankName, String bankAcc, String ifsc) {}

    public record UserReq(String username, String password, String role, String fullName, String mobile,
                          String state, String district, String ownerAdmin,
                          String aadhar, String pan, String dl, String bankName, String bankAcc, String ifsc) {}

    /** Which investigator takes which part. */
    public record PartAssign(String type, String investigatorUsername) {}

    /**
     * district = the PATIENT's district. hospitalDistrict = the hospital's district.
     * State/district pairs are captured for the patient, hospital, and (when applicable) police station.
     * partAssignments = investigator for each part; investigatorUsername is only a fallback for parts not listed.
     * hospitalStateAdmin / patientStateAdmin = routing choices for parts, validated against each location's state.
     */
    public record CaseReq(String tmcNumber, String patientName, String district, String village,
                          String doa, String dod, String hospitalName, String insuranceCompany,
                          String claimType, String billSource, String investigatorUsername,
                          String hospitalDistrict, List<PartAssign> partAssignments,
                          String stateAdmin, String patientState, String hospitalState,
                          String policeStationState, String policeStationDistrict,
                          String hospitalStateAdmin, String patientStateAdmin) {}

    /** Send back; parts = which parts to re-open (empty / null = all). */
    public record SendBackReq(String note, List<String> parts) {}

    /** Investigator asks to hand one part to someone else. */
    public record TransferReq(String reason, String target) {}

    /** decision = approve | deny | forward. */
    public record TransferDecisionReq(String decision, String investigatorUsername, String note) {}

    public record PartReq(String type, String answer, String reason, Boolean viaOther, String note,
                          Boolean mrdBillAvailable, BigDecimal mrdBillAmount) {}

    public record SaveReq(List<PartReq> parts, String remarks, String claimNo, String policyNo,
                          String patientAge, String ipNo, boolean submit) {}

    public record CompleteReq(String note, boolean ack) {}
}
