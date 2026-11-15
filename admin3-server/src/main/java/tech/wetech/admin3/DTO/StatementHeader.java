package tech.wetech.admin3.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class StatementHeader {
  private String bankName;            // 广西农商联合银行
  private String accountNo;           // 账号/卡号
  private String accountHolder;       // 户名
  private LocalDate startDate;        // 日期范围起
  private LocalDate endDate;          // 日期范围止
  private String currency;
}
