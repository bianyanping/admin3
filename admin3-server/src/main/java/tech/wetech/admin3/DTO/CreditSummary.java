package tech.wetech.admin3.DTO;

import lombok.Data;

import java.math.BigDecimal;

// ========== 信息概要 ==========
@Data
public class CreditSummary {
  private Integer totalAccounts;
  private Integer overdueAccounts;
  private BigDecimal totalCreditLimit;
  private BigDecimal totalUsedAmount;
  private String summaryRaw;      // 概要原文，便于兜底
}
