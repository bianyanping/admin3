package tech.wetech.admin3.ocrApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.ocrApi.model.BankStatement;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface BankStatementRepository extends JpaRepository<BankStatement, Long>, JpaSpecificationExecutor<BankStatement> {

  List<BankStatement> findByBankCode(String bankCode);

  List<BankStatement> findByAccountNo(String accountNo);

  /**
   * 判重查询：同一银行 + 同一账号 + 同一起止日期 = 同一份流水
   */
  Optional<BankStatement> findByBankCodeAndAccountNoAndStartDateAndEndDate(
    String bankCode, String accountNo, LocalDate startDate, LocalDate endDate);

  @Query("select count(t) from BankStatementTransaction t where t.statement.id = :statementId")
  long countTransactions(Long statementId);
}
