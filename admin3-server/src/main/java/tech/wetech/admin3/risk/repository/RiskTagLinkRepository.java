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

  Optional<RiskTagLink> findBySubjectTypeAndSubjectKeyAndTagIdAndOrgId(
    String subjectType, String subjectKey, Long tagId, Long orgId);

  /**
   * 统计可见的手工标签数量：org_id 落在当前可见组织集合内。
   */
  long countBySubjectTypeAndTagIdAndSourceAndOrgIdIn(String subjectType, Long tagId, String source,
                                                     Collection<Long> orgIds);

  @Modifying
  @Query("delete from RiskTagLink l where l.tagId = :tagId and l.source = :source")
  void deleteByTagIdAndSource(@Param("tagId") Long tagId, @Param("source") String source);

  List<RiskTagLink> findByTagIdAndSource(Long tagId, String source);
}
