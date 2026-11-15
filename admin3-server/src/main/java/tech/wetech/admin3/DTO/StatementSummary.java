package tech.wetech.admin3.DTO;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class StatementSummary {
  private BigDecimal totalExpense;    // 支出合计
  private Integer expenseCount;       // 支出笔数
  private BigDecimal totalIncome;     // 收入合计
  private Integer incomeCount;        // 收入笔数
  private String applyTime;           // 明细申请时间
}
