package tech.wetech.admin3.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

// ========== 信贷账户明细 ==========
@Data
public class CreditAccountDTO {
  private String accountType;     // 贷款/贷记卡/准贷记卡
  private String institution;     // 发放机构
  private String accountNo;       // 脱敏账号
  private BigDecimal creditLimit;
  private BigDecimal balance;     // 当前余额/透支余额
  private String status;          // 账户状态
  private String openDate;
  private List<OverdueRecord> overdueHistory;  // 逾期历史
}
