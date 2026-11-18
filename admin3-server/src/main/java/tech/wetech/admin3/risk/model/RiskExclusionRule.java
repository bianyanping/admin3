package tech.wetech.admin3.risk.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 消费型大众对手的剔除规则（关键词或名单）。
 *
 * <p>匹配方式：CONTAINS=名称包含关键词（如「美团」命中「美团平台商户」），
 * EXACT=名称完全一致（如「京东」只命中名称恰为「京东」的对手，
 * 保留「京东物流运输有限公司」这类公司主体）。
 */
@Entity
@Getter
@Setter
@Table(name = "risk_exclusion_rule",
  uniqueConstraints = @UniqueConstraint(name = "uk_risk_exclusion_keyword", columnNames = {"keyword", "match_type"}))
@Comment("消费型对手剔除规则：关键词 + 匹配方式，可增删启停")
public class RiskExclusionRule extends BaseEntity {

  public static final String MATCH_CONTAINS = "CONTAINS";
  public static final String MATCH_EXACT = "EXACT";

  @Comment("关键词或名单名称")
  @Column(nullable = false, length = 128)
  private String keyword;

  @Comment("匹配方式：CONTAINS=包含，EXACT=完全一致")
  @Column(name = "match_type", nullable = false, length = 16)
  private String matchType = MATCH_CONTAINS;

  @Comment("分类，如 电商/外卖/支付/出行")
  @Column(length = 64)
  private String category;

  @Comment("是否启用")
  @Column(nullable = false)
  private Boolean enabled = Boolean.TRUE;

  @Comment("是否系统预置：预置规则不可删除，可停用")
  @Column(nullable = false)
  private Boolean builtin = Boolean.FALSE;

  @Comment("说明")
  @Column(length = 255)
  private String remark;

  @Comment("创建时间")
  private LocalDateTime createTime;

  @PrePersist
  protected void onCreate() {
    createTime = LocalDateTime.now();
    if (enabled == null) {
      enabled = Boolean.TRUE;
    }
    if (builtin == null) {
      builtin = Boolean.FALSE;
    }
    if (matchType == null) {
      matchType = MATCH_CONTAINS;
    }
  }
}
