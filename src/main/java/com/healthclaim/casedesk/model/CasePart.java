package com.healthclaim.casedesk.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.math.BigDecimal;

@Entity
@Table(name = "case_parts")
public class CasePart {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "case_id")
    private CaseRecord caseRecord;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private PartType type;

    /** "Y", "N" or null (not answered yet). */
    @Column(length = 1)
    private String answer;

    /** Mandatory when answer = N. */
    @Column(length = 1000)
    private String reason;

    /** Answer is Y but the documents come through another person and still have to be received. */
    private boolean viaOther;

    /** Mandatory when viaOther = true (who / when the documents will arrive). */
    @Column(length = 1000)
    private String note;

    /** Who is responsible for THIS part (hospital and patient can be given to different investigators). */
    @Column(length = 80) private String ownerAdmin;
    @Column(length = 80) private String investigatorUsername;
    private String investigatorName;

    /** True once the responsible investigator has submitted this part (locked until sent back). */
    private boolean submitted;

    /** True once the responsible State Admin/Assistant has reviewed this submitted part. */
    private boolean reviewed;
    @Column(length = 80) private String reviewedBy;
    private Instant reviewedAt;
    @Column(length = 1000) private String reviewNote;

    /** The responsible investigator's own remarks (shown with their part). */
    @Column(length = 2000) private String remarks;

    private Boolean mrdBillAvailable;
    @Column(precision = 12, scale = 2) private BigDecimal mrdBillAmount;

    // ---- part transfer request (investigator -> admin) ----
    /** null = none, REQUESTED = waiting for admin/assistant, FORWARDED = passed on to the Main Admin (other state). */
    @Column(length = 12) private String transferStatus;
    @Column(length = 1000) private String transferReason;
    @Column(length = 200) private String transferTarget;        // e.g. "Hyderabad, Telangana" (optional hint)
    private Instant transferRequestedAt;
    @Column(length = 1000) private String transferDecisionNote; // why a request was denied
    @Column(length = 1000) private String transferHistory;      // "Transferred from A to B on date by X"

    public CasePart() {}
    public CasePart(CaseRecord c, PartType t) { this.caseRecord = c; this.type = t; }

    public Long getId() { return id; }
    public CaseRecord getCaseRecord() { return caseRecord; }
    public PartType getType() { return type; }
    public String getAnswer() { return answer; }
    public void setAnswer(String v) { this.answer = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public boolean isViaOther() { return viaOther; }
    public void setViaOther(boolean v) { this.viaOther = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getInvestigatorUsername() { return investigatorUsername; }
    public void setInvestigatorUsername(String v) { this.investigatorUsername = v; }
    public String getOwnerAdmin() { return ownerAdmin; }
    public void setOwnerAdmin(String v) { this.ownerAdmin = v; }
    public String getInvestigatorName() { return investigatorName; }
    public void setInvestigatorName(String v) { this.investigatorName = v; }
    public boolean isSubmitted() { return submitted; }
    public void setSubmitted(boolean v) { this.submitted = v; }
    public boolean isReviewed() { return reviewed; }
    public void setReviewed(boolean v) { this.reviewed = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { this.reviewedAt = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String v) { this.remarks = v; }
    public Boolean getMrdBillAvailable() { return mrdBillAvailable; }
    public void setMrdBillAvailable(Boolean v) { this.mrdBillAvailable = v; }
    public BigDecimal getMrdBillAmount() { return mrdBillAmount; }
    public void setMrdBillAmount(BigDecimal v) { this.mrdBillAmount = v; }
    public String getTransferStatus() { return transferStatus; }
    public void setTransferStatus(String v) { this.transferStatus = v; }
    public String getTransferReason() { return transferReason; }
    public void setTransferReason(String v) { this.transferReason = v; }
    public String getTransferTarget() { return transferTarget; }
    public void setTransferTarget(String v) { this.transferTarget = v; }
    public Instant getTransferRequestedAt() { return transferRequestedAt; }
    public void setTransferRequestedAt(Instant v) { this.transferRequestedAt = v; }
    public String getTransferDecisionNote() { return transferDecisionNote; }
    public void setTransferDecisionNote(String v) { this.transferDecisionNote = v; }
    public String getTransferHistory() { return transferHistory; }
    public void setTransferHistory(String v) { this.transferHistory = v; }

    /** Clear the investigator's work on this part (used when the part moves to somebody else). */
    public void resetWork() {
        this.answer = null; this.reason = null; this.viaOther = false; this.note = null;
        this.remarks = null; this.submitted = false; this.reviewed = false;
        this.reviewedBy = null; this.reviewedAt = null; this.reviewNote = null;
        this.mrdBillAvailable = null; this.mrdBillAmount = null;
    }
    public void clearTransfer() {
        this.transferStatus = null; this.transferReason = null; this.transferTarget = null; this.transferRequestedAt = null;
    }
}
