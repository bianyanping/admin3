package tech.wetech.admin3.lending.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 网贷平台关键词库：平台名称 + 匹配关键词，支持手工维护。
 *
 * <p>匹配口径：交易对手户名或摘要命中任一关键词，即视为该平台的网贷相关交易。
 */
@Entity
@Getter
@Setter
@Table(name = "lending_platform",
  uniqueConstraints = @UniqueConstraint(name = "uk_lending_platform_name", columnNames = {"name"}))
@Comment("网贷平台关键词库：平台名称加匹配关键词，可手工维护")
public class LendingPlatform extends BaseEntity {

  @Comment("平台/产品名称，如 借呗、微粒贷")
  @Column(nullable = false, length = 128)
  private String name;

  @Comment("匹配关键词，多个用英文逗号分隔；命中对手户名或摘要即判为网贷相关")
  @Column(nullable = false, length = 512)
  private String keywords;

  @Comment("是否启用：停用后不参与匹配")
  @Column(nullable = false)
  private Boolean enabled = Boolean.TRUE;

  @Comment("是否系统内置兜底平台：不可删除，仅在正式平台都未命中时参与匹配")
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
