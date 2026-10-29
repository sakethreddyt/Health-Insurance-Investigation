package com.healthclaim.casedesk.repository;

import com.healthclaim.casedesk.model.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findAllByOrderByIdDesc(Pageable pageable);
}
