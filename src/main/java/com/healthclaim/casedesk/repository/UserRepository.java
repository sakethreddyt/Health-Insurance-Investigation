package com.healthclaim.casedesk.repository;

import com.healthclaim.casedesk.model.AppUser;
import com.healthclaim.casedesk.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserRepository extends JpaRepository<AppUser, String> {
    List<AppUser> findByOwnerAdmin(String ownerAdmin);
    List<AppUser> findByRoleAndActiveTrue(Role role);
    boolean existsByRole(Role role);
}
