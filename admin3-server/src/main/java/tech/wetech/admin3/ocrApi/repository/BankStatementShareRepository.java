package tech.wetech.admin3.ocrApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.ocrApi.model.BankStatementShare;

import java.util.List;

public interface BankStatementShareRepository extends JpaRepository<BankStatementShare, Long> {

  List<BankStatementShare> findByStatementId(Long statementId);

  boolean existsByStatementIdAndGranteeUserId(Long statementId, Long granteeUserId);

  @Transactional
  void deleteByStatementIdAndGranteeUserId(Long statementId, Long granteeUserId);

  /**
   * 删除某流水单的全部分享记录（流水单删除时清理，避免悬挂数据）
   */
  @Transactional
  void deleteByStatementId(Long statementId);

  @Query("select s.statementId from BankStatementShare s where s.granteeUserId = :granteeUserId")
  List<Long> findStatementIdsByGranteeUserId(@Param("granteeUserId") Long granteeUserId);
}
