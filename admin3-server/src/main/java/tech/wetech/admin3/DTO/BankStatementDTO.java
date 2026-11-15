package tech.wetech.admin3.DTO;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 银行流水单（整份对账单）
 */
@Data
public class BankStatementDTO {
  private StatementHeader header;
  private List<TransactionDTO> transactions;
  private StatementSummary summary;   // 页尾的合计信息（仅最后一页有）
  private String rawOcrText;          // 原始文本，便于追溯
}





