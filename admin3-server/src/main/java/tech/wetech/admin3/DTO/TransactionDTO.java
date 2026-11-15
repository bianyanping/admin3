package tech.wetech.admin3.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
public class TransactionDTO {
  private Integer seqNo;              // 序号
  private LocalDate transDate;        // 交易日期
  private String transTime;           // 交易时间（HH:mm:ss）
  private LocalDateTime transDateTime;// 合并后的完整时间
  private BigDecimal amount;          // 金额（带符号：正=收入，负=支出）
  private BigDecimal balance;         // 账户余额
  private String direction;           // 借贷标志：出/入/收入/支出
  private String ocrDirection;        // OCR识别的借贷标志
  private String counterpartyAccount; // 对方账号
  private String counterpartyName;    // 对方户名
  private String channel;             // 交易渠道（跨行快汇转/农信银等）
  private String purpose;             // 用途
  private String summary;             // 摘要
  private String postscript;          // 附言
  private String remark;              // 备注
  private Boolean amountSignExplicit =false;  // 金额符号是否显式

}
