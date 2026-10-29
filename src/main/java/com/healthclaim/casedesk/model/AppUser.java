package com.healthclaim.casedesk.model;

import com.healthclaim.casedesk.config.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * One table for every kind of login (main admin, state admin, office assistant, investigator).
 * ownerAdmin is the "tenant": the username of the state admin (or main admin) whose data this belongs to.
 */
@Entity
@Table(name = "app_users", indexes = @Index(name = "idx_user_owner", columnList = "ownerAdmin"))
public class AppUser {

    @Id @Column(length = 80)
    private String username;

    @Column(nullable = false, length = 100)
    private String passwordHash;           // BCrypt — used for login

    /** ONLY for investigators: AES-GCM encrypted copy so an admin can view it. Never set for other roles. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 255)
    private String revealablePassword;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private Role role;

    private String fullName;
    private String mobile;
    private String state;
    private String district;

    @Column(nullable = false, length = 80)
    private String ownerAdmin;

    private boolean active = true;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24)
    private UserStatus approvalStatus = UserStatus.APPROVED;
    @Column(length = 1000)
    private String reviewNote;
    private String createdBy;

    // KYC — encrypted at rest
    @Convert(converter = EncryptedStringConverter.class) @Column(length = 255) private String aadhar;
    @Convert(converter = EncryptedStringConverter.class) @Column(length = 255) private String pan;
    @Convert(converter = EncryptedStringConverter.class) @Column(length = 255) private String dl;
    @Convert(converter = EncryptedStringConverter.class) @Column(length = 255) private String bankName;
    @Convert(converter = EncryptedStringConverter.class) @Column(length = 255) private String bankAcc;
    @Convert(converter = EncryptedStringConverter.class) @Column(length = 255) private String ifsc;

    private String photoFile;      // stored file name (not a path)
    private String aadharFile;

    private int failedAttempts;
    private Instant lockedUntil;
    private boolean mustChangePassword;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String v) { this.passwordHash = v; }
    public String getRevealablePassword() { return revealablePassword; }
    public void setRevealablePassword(String v) { this.revealablePassword = v; }
    public Role getRole() { return role; }
    public void setRole(Role v) { this.role = v; }
    public String getFullName() { return fullName; }
    public void setFullName(String v) { this.fullName = v; }
    public String getMobile() { return mobile; }
    public void setMobile(String v) { this.mobile = v; }
    public String getState() { return state; }
    public void setState(String v) { this.state = v; }
    public String getDistrict() { return district; }
    public void setDistrict(String v) { this.district = v; }
    public String getOwnerAdmin() { return ownerAdmin; }
    public void setOwnerAdmin(String v) { this.ownerAdmin = v; }
    public boolean isActive() { return active; }
    public void setActive(boolean v) { this.active = v; }
    public UserStatus getApprovalStatus() { return approvalStatus; }
    public void setApprovalStatus(UserStatus v) { this.approvalStatus = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public String getAadhar() { return aadhar; }
    public void setAadhar(String v) { this.aadhar = v; }
    public String getPan() { return pan; }
    public void setPan(String v) { this.pan = v; }
    public String getDl() { return dl; }
    public void setDl(String v) { this.dl = v; }
    public String getBankName() { return bankName; }
    public void setBankName(String v) { this.bankName = v; }
    public String getBankAcc() { return bankAcc; }
    public void setBankAcc(String v) { this.bankAcc = v; }
    public String getIfsc() { return ifsc; }
    public void setIfsc(String v) { this.ifsc = v; }
    public String getPhotoFile() { return photoFile; }
    public void setPhotoFile(String v) { this.photoFile = v; }
    public String getAadharFile() { return aadharFile; }
    public void setAadharFile(String v) { this.aadharFile = v; }
    public int getFailedAttempts() { return failedAttempts; }
    public void setFailedAttempts(int v) { this.failedAttempts = v; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant v) { this.lockedUntil = v; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean v) { this.mustChangePassword = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
