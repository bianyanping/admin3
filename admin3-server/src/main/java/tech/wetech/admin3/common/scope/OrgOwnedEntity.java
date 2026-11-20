package tech.wetech.admin3.common.scope;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

/**
 * 按组织归属的业务实体基类。
 *
 * <p>新增交易类数据只需继承本类，写入时自动取当前登录用户所属组织；
 * 查询侧只需叠加 {@link OrgScopeService#scoped(String)} 即可获得
 * 「本组织及下级组织可见」的过滤条件，避免各模块重复实现。
 *
 * <p>特殊场景（如系统全局标签）可重写 {@link #autoAssignOrg()} 返回 false，
 * 自行决定是否写入 org_id。
 */
@MappedSuperclass
public abstract class OrgOwnedEntity extends BaseEntity {

  @Comment("数据归属组织 id；写入时自动取当前用户组织，NULL 视为根组织/全局数据")
  @Column(name = "org_id")
  private Long orgId;

  public Long getOrgId() {
    return orgId;
  }

  public void setOrgId(Long orgId) {
    this.orgId = orgId;
  }

  @PrePersist
  protected void applyOrgScope() {
    if (orgId == null && autoAssignOrg()) {
      orgId = OrgScopeContext.orgId();
    }
  }

  /**
   * 是否在持久化前自动填充当前组织。
   */
  protected boolean autoAssignOrg() {
    return true;
  }
}
