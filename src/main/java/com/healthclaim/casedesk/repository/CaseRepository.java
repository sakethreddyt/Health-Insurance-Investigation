package com.healthclaim.casedesk.repository;

import com.healthclaim.casedesk.model.CaseRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface CaseRepository extends JpaRepository<CaseRecord, Long> {
    List<CaseRecord> findByOwnerAdmin(String ownerAdmin);
    List<CaseRecord> findByInvestigatorUsername(String username);
    boolean existsByOwnerAdminAndTmcNumberIgnoreCase(String ownerAdmin, String tmcNumber);
    @Query("select distinct c from CaseRecord c left join c.parts p "
         + "where c.ownerAdmin = :owner or p.ownerAdmin = :owner "
         + "or (p.ownerAdmin is null and coalesce(p.investigatorUsername, c.investigatorUsername) in "
         + "(select u.username from AppUser u where u.ownerAdmin = :owner)) "
         + "or c.createdBy = :username")
    List<CaseRecord> findVisibleToStateAdmin(@Param("owner") String owner, @Param("username") String username);

    /** Every case in which at least one part is assigned to this investigator (older rows without a per-part owner fall back to the case's investigator). */
    @Query("select distinct c from CaseRecord c join c.parts p "
         + "where p.investigatorUsername = :u or (p.investigatorUsername is null and c.investigatorUsername = :u)")
    List<CaseRecord> findForInvestigator(@Param("u") String username);
}
