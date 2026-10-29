package com.healthclaim.casedesk.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "cases",
       uniqueConstraints = @UniqueConstraint(name = "uk_case_tmc", columnNames = {"ownerAdmin", "tmcNumber"}),
       indexes = {@Index(name = "idx_case_owner", columnList = "ownerAdmin"),
                  @Index(name = "idx_case_inv", columnList = "investigatorUsername")})
public class CaseRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60) private String tmcNumber;     // TMCID
    @Column(nullable = false) private String patientName;
    private String district;
    @Column(length = 80) private String patientState;
    private String village;
    /** District of the hospital (the patient's district is "district"). */
    private String hospitalDistrict;
    @Column(length = 80) private String hospitalState;
    @Column(length = 80) private String policeStationState;
    @Column(length = 80) private String policeStationDistrict;
    private LocalDate doa;
    private LocalDate dod;
    private String hospitalName;
    private String insuranceCompany;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private ClaimType claimType;
    /** MEDICAL_BILL_VERIFICATION only: HOSPITAL or MEDICAL_SHOP. */
    @Column(length = 20) private String billSource;

    @Column(nullable = false, length = 80) private String ownerAdmin;
    @Column(nullable = false, length = 80) private String investigatorUsername;
    private String investigatorName;
    private String createdBy;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private CaseStatus status = CaseStatus.ASSIGNED;

    // investigator's submission
    @Column(length = 2000) private String remarks;
    private boolean flagged;                 // remarks present / a "No" / documents via another person
    private Instant submittedAt;

    // optional extras the letters use
    private String claimNo;
    private String policyNo;
    private String patientAge;
    private String ipNo;

    // send-back (by admin / office assistant)
    @Column(length = 1000) private String sentBackReason;
    private String sentBackBy;
    private Instant sentBackAt;

    // completion
    @Column(length = 1000) private String reviewNote;
    private String reviewedBy;
    private Instant reviewedAt;

    // request-for-edit (investigator -> admin)
    private boolean editRequested;
    private Instant editRequestedAt;
    private String editRequestedBy;
    @Column(length = 1000) private String editRequestReason;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean newCaseNotification = true;

    @ElementCollection
    @CollectionTable(name = "case_viewed_by", joinColumns = @JoinColumn(name = "case_id"))
    @Column(name = "username", nullable = false, length = 80)
    private Set<String> viewedBy = new HashSet<>();

    @OneToMany(mappedBy = "caseRecord", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<CasePart> parts = new ArrayList<>();

    public Long getId() { return id; }
    public String getTmcNumber() { return tmcNumber; }
    public void setTmcNumber(String v) { this.tmcNumber = v; }
    public String getPatientName() { return patientName; }
    public void setPatientName(String v) { this.patientName = v; }
    public String getDistrict() { return district; }
    public void setDistrict(String v) { this.district = v; }
    public String getPatientState() { return patientState; }
    public void setPatientState(String v) { this.patientState = v; }
    public String getHospitalDistrict() { return hospitalDistrict; }
    public void setHospitalDistrict(String v) { this.hospitalDistrict = v; }
    public String getHospitalState() { return hospitalState; }
    public void setHospitalState(String v) { this.hospitalState = v; }
    public String getPoliceStationState() { return policeStationState; }
    public void setPoliceStationState(String v) { this.policeStationState = v; }
    public String getPoliceStationDistrict() { return policeStationDistrict; }
    public void setPoliceStationDistrict(String v) { this.policeStationDistrict = v; }
    public String getEditRequestedBy() { return editRequestedBy; }
    public void setEditRequestedBy(String v) { this.editRequestedBy = v; }
    public String getVillage() { return village; }
    public void setVillage(String v) { this.village = v; }
    public LocalDate getDoa() { return doa; }
    public void setDoa(LocalDate v) { this.doa = v; }
    public LocalDate getDod() { return dod; }
    public void setDod(LocalDate v) { this.dod = v; }
    public String getHospitalName() { return hospitalName; }
    public void setHospitalName(String v) { this.hospitalName = v; }
    public String getInsuranceCompany() { return insuranceCompany; }
    public void setInsuranceCompany(String v) { this.insuranceCompany = v; }
    public ClaimType getClaimType() { return claimType; }
    public void setClaimType(ClaimType v) { this.claimType = v; }
    public String getBillSource() { return billSource; }
    public void setBillSource(String v) { this.billSource = v; }
    public String getOwnerAdmin() { return ownerAdmin; }
    public void setOwnerAdmin(String v) { this.ownerAdmin = v; }
    public String getInvestigatorUsername() { return investigatorUsername; }
    public void setInvestigatorUsername(String v) { this.investigatorUsername = v; }
    public String getInvestigatorName() { return investigatorName; }
    public void setInvestigatorName(String v) { this.investigatorName = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public CaseStatus getStatus() { return status; }
    public void setStatus(CaseStatus v) { this.status = v; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String v) { this.remarks = v; }
    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean v) { this.flagged = v; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public String getClaimNo() { return claimNo; }
    public void setClaimNo(String v) { this.claimNo = v; }
    public String getPolicyNo() { return policyNo; }
    public void setPolicyNo(String v) { this.policyNo = v; }
    public String getPatientAge() { return patientAge; }
    public void setPatientAge(String v) { this.patientAge = v; }
    public String getIpNo() { return ipNo; }
    public void setIpNo(String v) { this.ipNo = v; }
    public String getSentBackReason() { return sentBackReason; }
    public void setSentBackReason(String v) { this.sentBackReason = v; }
    public String getSentBackBy() { return sentBackBy; }
    public void setSentBackBy(String v) { this.sentBackBy = v; }
    public Instant getSentBackAt() { return sentBackAt; }
    public void setSentBackAt(Instant v) { this.sentBackAt = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { this.reviewedAt = v; }
    public boolean isEditRequested() { return editRequested; }
    public void setEditRequested(boolean v) { this.editRequested = v; }
    public Instant getEditRequestedAt() { return editRequestedAt; }
    public void setEditRequestedAt(Instant v) { this.editRequestedAt = v; }
    public String getEditRequestReason() { return editRequestReason; }
    public void setEditRequestReason(String v) { this.editRequestReason = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public boolean isNewCaseNotification() { return newCaseNotification; }
    public Set<String> getViewedBy() { return viewedBy; }
    public List<CasePart> getParts() { return parts; }
}
