package tech.wetech.admin3.lending.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.common.scope.OrgOwnedEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 网贷命中记录：同一组织内一笔流水最多命中一条（唯一约束 org_id + transactionId），
 * 保存命中的平台、关键词与流水快照，便于统计明细追溯。
 *
 * <p>该表是派生数据，可由「重新匹配」按组织重建，不对流水表做任何修改。
 */
@Entity
@Getter
@Setter
@Table(name = "lending_transaction_match",
  uniqueConstraints = @UniqueConstraint(name = "uk_lending_match_org_tx", columnNames = {"org_id", "transactionId"}),
  indexes = {
    @Index(name = "idx_lending_match_month", columnList = "transMonth"),
    @Index(name = "idx_lending_match_platform", columnList = "platformId"),
    @Index(name = "idx_lending_match_direction", columnList = "direction"),
    @Index(name = "idx_lending_match_statement", columnList = "statementId"),
    @Index(name = "idx_lending_match_holder", columnList = "accountHolder"),
    @Index(name = "idx_lending_match_org", columnList = "org_id")
  })
@Comment("网贷命中记录：按组织沉淀，一笔流水在每个组织最多一条，可整表重建")
public class LendingTransactionMatch extends OrgOwnedEntity {

  @Comment("银行流水明细 id（bank_statement_transaction.id）")
  @Column(nullable = false)
  private Long transactionId;

  @Comment("所属流水单 id（bank_statement.id）")
  @Column(nullable = false)
  private Long statementId;

  @Comment("账户户名快照，作为客户归属维度（bank_statement.account_holder）")
  @Column(length = 128)
  private String accountHolder;

  @Comment("银行名称快照")
  @Column(length = 128)
  private String bankName;

  @Comment("账号快照")
  @Column(length = 64)
  private String accountNo;

  @Comment("交易日期快照")
  private LocalDate transDate;

  @Comment("交易月份（yyyy-MM），按交易日期生成")
  @Column(length = 7)
  private String transMonth;

  @Comment("交易金额快照（带符号：正数=收入，负数=支出）")
  @Column(precision = 18, scale = 2)
  private BigDecimal amount;

  @Comment("借贷标志快照：入=收入，出=支出，其他=不计收支")
  @Column(length = 8)
  private String direction;

  @Comment("对方户名快照")
  @Column(length = 128)
  private String counterpartyName;

  @Comment("摘要快照")
  @Column(length = 500)
  private String summary;

  @Comment("命中的平台 id")
  @Column(nullable = false)
  private Long platformId;

  @Comment("命中的平台名称快照")
  @Column(nullable = false, length = 128)
  private String platformName;

  @Comment("命中的关键词")
  @Column(nullable = false, length = 64)
  private String keyword;

  @Comment("命中字段：COUNTERPARTY_NAME=对方户名，SUMMARY=摘要")
  @Column(nullable = false, length = 32)
  private String matchedField;

  @Comment("匹配时间")
  private LocalDateTime createTime;

  @PrePersist
  protected void onCreate() {
    createTime = LocalDateTime.now();
  }
}
