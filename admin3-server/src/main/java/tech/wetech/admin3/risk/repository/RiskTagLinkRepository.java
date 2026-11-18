package tech.wetech.admin3.risk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.risk.model.RiskTagLink;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RiskTagLinkRepository extends JpaRepository<RiskTagLink, Long> {

  List<RiskTagLink> findBySubjectTypeAndSubjectKeyIn(String subjectType, Collection<String> subjectKeys);

  List<RiskTagLink> findBySubjectType(String subjectType);

  List<RiskTagLink> findBySubjectTypeAndSubjectKeyAndTagId(String subjectType, String subjectKey, Long tagId);

  Optional<RiskTagLink> findBySubjectTypeAndSubjectKeyAndTagIdAndOwnerUserId(
    String subjectType, String subjectKey, Long tagId, Long ownerUserId);

  long countBySubjectTypeAndTagId(String subjectType, Long tagId);

  long countBySubjectTypeAndTagIdAndOwnerUserId(String subjectType, Long tagId, Long ownerUserId);

  @Modifying
  @Query("delete from RiskTagLink l where l.tagId = :tagId and l.source = :source")
  void deleteByTagIdAndSource(@Param("tagId") Long tagId, @Param("source") String source);

  List<RiskTagLink> findByTagIdAndSource(Long tagId, String source);
}
