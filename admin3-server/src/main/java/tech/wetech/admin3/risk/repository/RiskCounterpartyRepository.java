package tech.wetech.admin3.risk.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.common.scope.OrgScopedRepository;
import tech.wetech.admin3.risk.model.RiskCounterparty;

import java.util.List;

@Repository
public interface RiskCounterpartyRepository extends OrgScopedRepository<RiskCounterparty, Long> {

  List<RiskCounterparty> findByConsumer(Boolean consumer);

  List<RiskCounterparty> findByOrgId(Long orgId);

  List<RiskCounterparty> findByOrgIdAndConsumer(Long orgId, Boolean consumer);

  long countByOrgId(Long orgId);

  @Modifying
  @Query("delete from RiskCounterparty c where c.orgId = :orgId")
  void deleteByOrgId(@Param("orgId") Long orgId);
}
