package tech.wetech.admin3.DTO;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OverdueRecord {
  private String month;           // 逾期月份
  private Integer overdueMonths;  // 逾期持续月数
  private BigDecimal overdueAmount;
}
