package tech.wetech.admin3.risk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.risk.model.RiskExclusionRule;

import java.util.List;
import java.util.Optional;

@Repository
public interface RiskExclusionRuleRepository extends JpaRepository<RiskExclusionRule, Long> {

  List<RiskExclusionRule> findAllByOrderByIdAsc();

  List<RiskExclusionRule> findByEnabledTrueOrderByIdAsc();

  Optional<RiskExclusionRule> findByKeywordAndMatchType(String keyword, String matchType);

  List<RiskExclusionRule> findByKeyword(String keyword);
}
