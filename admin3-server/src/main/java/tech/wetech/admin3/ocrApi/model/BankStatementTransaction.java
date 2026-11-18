package tech.wetech.admin3.ocrApi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 银行流水交易明细。
 */
@Entity
@Getter
@Setter
@Table(name = "bank_statement_transaction", indexes = {
  @Index(name = "idx_bank_statement_tx_statement", columnList = "statement_id"),
  @Index(name = "idx_bank_statement_tx_date", columnList = "transDate")
})
@Comment("银行流水交易明细：一份对账单下的逐笔交易记录")
public class BankStatementTransaction extends BaseEntity {

  @Comment("所属银行流水单（bank_statement.id）")
  @JsonIgnore
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "statement_id")
  private BankStatement statement;

  /**
   * 序号
   */
  @Comment("交易序号（PDF 明细中的行号）")
  private Integer seqNo;

  @Comment("交易日期")
  private LocalDate transDate;

  /**
   * 交易时间（HH:mm:ss）
   */
  @Comment("交易时间（HH:mm:ss，部分银行无此列）")
  @Column(length = 16)
  private String transTime;

  /**
   * 合并后的完整交易时间
   */
  @Comment("完整交易时间（交易日期+交易时间合并）")
  private LocalDateTime transDateTime;

  /**
   * 金额（带符号：正=收入，负=支出）
   */
  @Comment("交易金额（带符号：正数=收入，负数=支出）")
  @Column(precision = 18, scale = 2)
  private BigDecimal amount;

  /**
   * 账户余额
   */
  @Comment("交易后账户余额")
  @Column(precision = 18, scale = 2)
  private BigDecimal balance;

  /**
   * 借贷标志：出/入
   */
  @Comment("借贷标志：出=支出，入=收入")
  @Column(length = 8)
  private String direction;

  /**
   * 数据源原始方向（如微信证明的「其他」），direction 为按业务口径映射后的入/出
   */
  @Comment("数据源原始方向（如微信证明的「其他」）")
  @Column(length = 8)
  private String rawDirection;

  /**
   * OCR 识别的借贷标志
   */
  @Comment("OCR 原始识别出的借贷标志（用于和 direction 比对校验）")
  @Column(length = 8)
  private String ocrDirection;

  @Comment("对方账号")
  @Column(length = 64)
  private String counterpartyAccount;

  @Comment("对方户名")
  @Column(length = 128)
  private String counterpartyName;

  @Comment("交易渠道（如网银/手机银行/柜面/ATM）")
  @Column(length = 64)
  private String channel;

  @Comment("用途")
  @Column(length = 500)
  private String purpose;

  @Comment("摘要")
  @Column(length = 500)
  private String summary;

  @Comment("附言")
  @Column(length = 500)
  private String postscript;

  @Comment("备注")
  @Column(length = 500)
  private String remark;

  /**
   * 金额符号是否显式
   */
  @Comment("金额正负号是否由 PDF 显式给出（false=靠借贷标志推导）")
  private Boolean amountSignExplicit;
}
