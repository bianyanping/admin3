package tech.wetech.admin3.ocrApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.ocrApi.model.BankStatementTransaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Repository
public interface BankStatementTransactionRepository
  extends JpaRepository<BankStatementTransaction, Long>, JpaSpecificationExecutor<BankStatementTransaction> {

  /**
   * 网贷匹配所需的流水投影：只取匹配用到的字段与所属流水单快照，避免加载大字段。
   */
  @Query("""
    select t.id as id, s.id as statementId, s.bankName as bankName,
           s.accountHolder as accountHolder, s.accountNo as accountNo,
           t.transDate as transDate, t.amount as amount, t.direction as direction,
           t.counterpartyName as counterpartyName, t.summary as summary
    from BankStatementTransaction t join t.statement s
    """)
  List<LendingSourceView> findLendingSources();

  /**
   * 网贷匹配数据源（只读投影）。
   */
  interface LendingSourceView {
    Long getId();

    Long getStatementId();

    String getBankName();

    String getAccountHolder();

    String getAccountNo();

    LocalDate getTransDate();

    BigDecimal getAmount();

    String getDirection();

    String getCounterpartyName();

    String getSummary();
  }
}
