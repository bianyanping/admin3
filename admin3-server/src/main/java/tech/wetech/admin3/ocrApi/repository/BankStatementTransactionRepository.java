package tech.wetech.admin3.ocrApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.ocrApi.model.BankStatementTransaction;

@Repository
public interface BankStatementTransactionRepository
  extends JpaRepository<BankStatementTransaction, Long>, JpaSpecificationExecutor<BankStatementTransaction> {
}
