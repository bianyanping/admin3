package tech.wetech.admin3.risk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.risk.model.RiskCounterparty;

import java.util.List;

@Repository
public interface RiskCounterpartyRepository extends JpaRepository<RiskCounterparty, Long>,
  JpaSpecificationExecutor<RiskCounterparty> {

  long countByConsumer(Boolean consumer);

  List<RiskCounterparty> findByConsumer(Boolean consumer);

  List<RiskCounterparty> findByOwnerUserId(Long ownerUserId);

  long countByOwnerUserId(Long ownerUserId);

  @Modifying
  @Query("delete from RiskCounterparty c where c.ownerUserId = :ownerUserId")
  void deleteByOwnerUserId(@Param("ownerUserId") Long ownerUserId);
}
