package tech.wetech.admin3.risk.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

/**
 * 风险标签定义：区分主体类型（交易对手 / 客户）。
 *
 * <p>标签本身不区分手工还是系统，来源记录在 {@link RiskTagLink#getSource()}。
 */
@Entity
@Getter
@Setter
@Table(name = "risk_tag",
  uniqueConstraints = @UniqueConstraint(name = "uk_risk_tag_code", columnNames = "code"))
@Comment("风险标签定义：标签编码、名称、适用主体类型与颜色")
public class RiskTag extends BaseEntity {

  public static final String SUBJECT_COUNTERPARTY = "COUNTERPARTY";
  public static final String SUBJECT_CUSTOMER = "CUSTOMER";

  @Comment("标签编码，如 FUNDER / BLACKLIST_CUSTOMER / CONSUMER")
  @Column(nullable = false, length = 64)
  private String code;

  @Comment("标签名称，如 民间资方 / 黑名单客户")
  @Column(nullable = false, length = 64)
  private String name;

  @Comment("适用主体类型：COUNTERPARTY=交易对手，CUSTOMER=客户")
  @Column(name = "subject_type", nullable = false, length = 16)
  private String subjectType;

  @Comment("标签颜色（Element Plus 语义色：danger/warning/success/info/primary）")
  @Column(length = 32)
  private String color;

  @Comment("是否系统预置标签")
  @Column(nullable = false)
  private Boolean builtin = Boolean.FALSE;

  @Comment("说明")
  @Column(length = 255)
  private String description;

  @Comment("排序号")
  private Integer sortOrder;
}
