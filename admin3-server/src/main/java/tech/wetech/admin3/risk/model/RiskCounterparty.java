package tech.wetech.admin3.risk.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.common.scope.OrgOwnedEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交易对手主数据：由流水自动沉淀，名称 + 卡号/账号。
 *
 * <p>主键维度为清洗后的对手名称（nameKey），同一名称下挂多个账号。
 * 消费型大众对手（抖音、美团、拼多多、淘宝、京东、微信、支付宝、饿了么等）
 * 由可配置的剔除规则判定，consumer=true 表示不进入默认列表。
 *
 * <p>数据按组织归属：每个组织按本组织子树流水沉淀一份，用户只能查看本组织及下级组织的数据。
 */
@Entity
@Getter
@Setter
@Table(name = "risk_counterparty",
  uniqueConstraints = @UniqueConstraint(name = "uk_risk_counterparty_org_name",
    columnNames = {"org_id", "name_key"}),
  indexes = {
    @Index(name = "idx_risk_counterparty_consumer", columnList = "consumer"),
    @Index(name = "idx_risk_counterparty_txn_count", columnList = "txnCount"),
    @Index(name = "idx_risk_counterparty_org", columnList = "org_id")
  })
@Comment("交易对手主数据：按组织子树流水沉淀，一行=一个清洗后的对手名称（含多个账号）")
public class RiskCounterparty extends OrgOwnedEntity {

  @Comment("清洗后的对手名称（去空白+小写），与归属组织共同构成唯一键，标签挂载依据")
  @Column(name = "name_key", nullable = false, length = 255)
  private String nameKey;

  @Comment("展示用对手名称（出现频次最高的原始名称）")
  @Column(length = 255)
  private String displayName;

  @Comment("对手卡号/账号，多个用逗号分隔")
  @Column(length = 1024)
  private String accountNos;

  @Comment("对手账号个数")
  private Integer accountCount;

  @Comment("交易笔数")
  private Integer txnCount;

  @Comment("入账笔数")
  private Integer incomeCount;

  @Comment("入账合计")
  @Column(precision = 18, scale = 2)
  private BigDecimal incomeTotal;

  @Comment("出账笔数")
  private Integer expenseCount;

  @Comment("出账合计")
  @Column(precision = 18, scale = 2)
  private BigDecimal expenseTotal;

  @Comment("涉及客户（户主）数")
  private Integer holderCount;

  @Comment("最早交易日期")
  private LocalDate firstDate;

  @Comment("最近交易日期")
  private LocalDate lastDate;

  @Comment("是否消费型大众对手：true=已剔除，不进入默认对手列表")
  @Column(nullable = false)
  private Boolean consumer = Boolean.FALSE;

  @Comment("命中的剔除规则关键词，多个用逗号分隔")
  @Column(length = 512)
  private String consumerRules;

  @Comment("本次沉淀时间")
  private LocalDateTime syncTime;
}
