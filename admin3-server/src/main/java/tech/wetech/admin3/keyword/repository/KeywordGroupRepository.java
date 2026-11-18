package tech.wetech.admin3.keyword.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.keyword.model.KeywordGroup;

import java.util.List;
import java.util.Optional;

@Repository
public interface KeywordGroupRepository extends JpaRepository<KeywordGroup, Long> {

  List<KeywordGroup> findAllByOrderByIdAsc();

  List<KeywordGroup> findAllByOwnerUserIdOrderByIdAsc(Long ownerUserId);

  Optional<KeywordGroup> findByName(String name);

  Optional<KeywordGroup> findByNameAndOwnerUserId(String name, Long ownerUserId);

  long countByOwnerUserId(Long ownerUserId);
}
