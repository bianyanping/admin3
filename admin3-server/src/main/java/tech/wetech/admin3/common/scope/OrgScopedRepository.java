package tech.wetech.admin3.common.scope;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

/**
 * 组织隔离实体的仓储基础接口。
 *
 * <p>需要按组织过滤时，优先复用 {@link OrgScopeService#scoped(String)}
 * 返回的统一 Specification，而不是在各自 Repository 中手写组织条件。
 */
@NoRepositoryBean
public interface OrgScopedRepository<T, ID> extends JpaRepository<T, ID>, JpaSpecificationExecutor<T> {

  /**
   * 查询当前用户可见范围内的全部数据。
   *
   * @param scopeService   组织范围服务
   * @param orgIdAttribute 实体中组织字段名，一般为 orgId
   */
  default List<T> findAllInScope(OrgScopeService scopeService, String orgIdAttribute) {
    return findAll(scopeService.scoped(orgIdAttribute));
  }
}
