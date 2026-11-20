package tech.wetech.admin3.lending.repository;

import org.springframework.stereotype.Repository;
import tech.wetech.admin3.common.scope.OrgScopedRepository;
import tech.wetech.admin3.lending.model.LendingPlatform;

import java.util.List;
import java.util.Optional;

@Repository
public interface LendingPlatformRepository extends OrgScopedRepository<LendingPlatform, Long> {

  Optional<LendingPlatform> findByNameAndOrgId(String name, Long orgId);

  boolean existsByNameAndOrgId(String name, Long orgId);

  Optional<LendingPlatform> findByIdAndOrgId(Long id, Long orgId);

  List<LendingPlatform> findByEnabledTrueAndOrgIdOrderByIdAsc(Long orgId);

  long countByOrgId(Long orgId);
}
