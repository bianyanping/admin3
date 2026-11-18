package tech.wetech.admin3.risk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.risk.model.RiskTag;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiskTagRepository extends JpaRepository<RiskTag, Long> {

  Optional<RiskTag> findByCode(String code);

  List<RiskTag> findAllByOrderBySortOrderAscIdAsc();

  List<RiskTag> findBySubjectTypeOrderBySortOrderAscIdAsc(String subjectType);
}
