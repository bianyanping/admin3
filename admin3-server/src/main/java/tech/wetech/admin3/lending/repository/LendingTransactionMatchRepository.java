package tech.wetech.admin3.lending.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.lending.model.LendingTransactionMatch;

@Repository
public interface LendingTransactionMatchRepository
  extends JpaRepository<LendingTransactionMatch, Long>, JpaSpecificationExecutor<LendingTransactionMatch> {

  /**
   * 统计缺少客户归属的旧命中记录：流水单本身有户名、但命中快照为空，说明是升级前生成的。
   * 流水单本身没有户名的合法情况不会命中该条件，避免每次启动重复重建。
   */
  @Query("""
    select count(m) from LendingTransactionMatch m
    where m.accountHolder is null
      and exists (select 1 from BankStatement s
                  where s.id = m.statementId
                    and s.accountHolder is not null and s.accountHolder <> '')
    """)
  long countStaleMatches();
}
