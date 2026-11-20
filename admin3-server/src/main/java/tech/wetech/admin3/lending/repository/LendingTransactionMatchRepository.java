package tech.wetech.admin3.lending.repository;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.common.scope.OrgScopedRepository;
import tech.wetech.admin3.lending.model.LendingTransactionMatch;

@Repository
public interface LendingTransactionMatchRepository extends OrgScopedRepository<LendingTransactionMatch, Long> {

  long countByOrgId(Long orgId);

  @Modifying
  @Query("delete from LendingTransactionMatch m where m.orgId = :orgId")
  void deleteByOrgId(@Param("orgId") Long orgId);

  /**
   * 统计指定组织内缺少客户归属的旧命中记录：流水单本身有户名、但命中快照为空，说明是升级前生成的。
   * 流水单本身没有户名的合法情况不会命中该条件，避免每次访问重复重建。
   */
  @Query("""
    select count(m) from LendingTransactionMatch m
    where m.orgId = :orgId
      and m.accountHolder is null
      and exists (select 1 from BankStatement s
                  where s.id = m.statementId
                    and s.accountHolder is not null and s.accountHolder <> '')
    """)
  long countStaleMatchesByOrgId(@Param("orgId") Long orgId);
}
