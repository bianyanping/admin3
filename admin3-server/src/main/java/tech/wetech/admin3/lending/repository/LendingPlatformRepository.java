package tech.wetech.admin3.lending.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.lending.model.LendingPlatform;

import java.util.List;
import java.util.Optional;

@Repository
public interface LendingPlatformRepository
  extends JpaRepository<LendingPlatform, Long>, JpaSpecificationExecutor<LendingPlatform> {

  Optional<LendingPlatform> findByName(String name);

  boolean existsByName(String name);

  List<LendingPlatform> findByEnabledTrueOrderByIdAsc();
}
