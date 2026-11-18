package tech.wetech.admin3.keyword.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 流水关键词分组：组名 + 关键词 + 匹配字段 + 方向范围，支持手工维护。
 *
 * <p>匹配口径：在启用的匹配字段（备注/摘要）中命中任一关键词，且方向在指定范围内。
 */
@Entity
@Getter
@Setter
@Table(name = "keyword_group",
  uniqueConstraints = @UniqueConstraint(name = "uk_keyword_group_owner_name",
    columnNames = {"owner_user_id", "name"}))
@Comment("流水关键词分组：按创建人归属，组名、关键词、匹配字段与方向范围，可手工维护")
public class KeywordGroup extends BaseEntity {

  @Comment("归属用户 id：分组只对创建人可见可用，存量数据归属 admin")
  @Column(name = "owner_user_id")
  private Long ownerUserId;

  @Comment("分组名称，如 借款/还款、工资/货款")
  @Column(nullable = false, length = 128)
  private String name;

  @Comment("关键词，多个用逗号分隔")
  @Column(nullable = false, length = 512)
  private String keywords;

  @Comment("匹配字段：remark=备注，summary=摘要，多个用逗号分隔")
  @Column(nullable = false, length = 128)
  private String fields = "remark,summary";

  @Comment("方向范围：ALL=全部，IN=仅入账，OUT=仅出账")
  @Column(nullable = false, length = 16)
  private String direction = "ALL";

  @Comment("是否启用：停用后不参与统计")
  @Column(nullable = false)
  private Boolean enabled = Boolean.TRUE;

  @Comment("是否系统预置分组：不可删除，可修改关键词与启停")
  @Column(nullable = false)
  private Boolean builtin = Boolean.FALSE;

  @Comment("备注")
  @Column(length = 255)
  private String remark;

  @Comment("创建时间")
  private LocalDateTime createTime;

  @Comment("更新时间")
  private LocalDateTime updateTime;

  @PrePersist
  protected void onCreate() {
    createTime = LocalDateTime.now();
    updateTime = createTime;
    if (enabled == null) {
      enabled = Boolean.TRUE;
    }
    if (builtin == null) {
      builtin = Boolean.FALSE;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    updateTime = LocalDateTime.now();
  }
}
