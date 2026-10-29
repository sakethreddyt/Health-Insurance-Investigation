package com.healthclaim.casedesk.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_log", indexes = @Index(name = "idx_audit_at", columnList = "at"))
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Instant at = Instant.now();
    @Column(length = 80) private String actor;
    @Column(length = 60) private String action;
    @Column(length = 120) private String target;
    @Column(length = 500) private String detail;
    @Column(length = 64) private String ip;

    public Long getId() { return id; }
    public Instant getAt() { return at; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getTarget() { return target; }
    public void setTarget(String v) { this.target = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public String getIp() { return ip; }
    public void setIp(String v) { this.ip = v; }
}
