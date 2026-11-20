package tech.wetech.admin3.risk.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.common.scope.OrgOwnedEntity;

import java.time.LocalDateTime;

/**
 * 标签挂载关系：一个主体可挂多个标签，来源区分手工/系统规则。
 *
 * <p>subjectKey 统一使用清洗后的名称（去空白+小写）：对手取对手名称，客户取户名。
 *
 * <p>数据按组织归属：手工标签归属标记人所在组织，读取时可见本组织及下级组织的标签；
 * 系统规则标签不写组织（全局共享）。
 */
@Entity
@Getter
@Setter
@Table(name = "risk_tag_link",
  uniqueConstraints = @UniqueConstraint(name = "uk_risk_tag_link_org",
    columnNames = {"subject_type", "subject_key", "tag_id", "org_id"}),
  indexes = @Index(name = "idx_risk_tag_link_subject", columnList = "subject_type,subject_key"))
@Comment("标签挂载关系：主体（对手/客户）+ 标签，手工标签按组织归属，系统标签全局可见")
public class RiskTagLink extends OrgOwnedEntity {

  public static final String SOURCE_MANUAL = "MANUAL";
  public static final String SOURCE_SYSTEM = "SYSTEM";

  @Comment("创建人用户 id：仅作审计留痕，可见范围以 org_id 为准")
  @Column(name = "owner_user_id")
  private Long ownerUserId;

  @Comment("主体类型：COUNTERPARTY=交易对手，CUSTOMER=客户")
  @Column(name = "subject_type", nullable = false, length = 16)
  private String subjectType;

  @Comment("主体键：清洗后的名称（去空白+小写）")
  @Column(name = "subject_key", nullable = false, length = 255)
  private String subjectKey;

  @Comment("标签 id（risk_tag.id）")
  @Column(name = "tag_id", nullable = false)
  private Long tagId;

  @Comment("标签来源：MANUAL=手工标记，SYSTEM=系统规则")
  @Column(nullable = false, length = 16)
  private String source = SOURCE_MANUAL;

  @Comment("标记人")
  @Column(length = 64)
  private String createUser;

  @Comment("标记时间")
  private LocalDateTime createTime;

  @PrePersist
  protected void onCreate() {
    createTime = LocalDateTime.now();
    if (source == null) {
      source = SOURCE_MANUAL;
    }
  }

  /**
   * 系统规则标签全局共享，不写入组织；手工标签自动归属当前用户组织。
   */
  @Override
  protected boolean autoAssignOrg() {
    return !SOURCE_SYSTEM.equals(source);
  }
}
