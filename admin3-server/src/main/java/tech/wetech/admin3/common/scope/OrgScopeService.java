package tech.wetech.admin3.common.scope;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.sys.model.Organization;
import tech.wetech.admin3.sys.repository.OrganizationRepository;
import tech.wetech.admin3.sys.repository.UserRepository;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 组织层级数据隔离的统一入口。
 *
 * <p>隔离规则：
 * <ul>
 *   <li>写入：业务数据自动归属当前登录用户所在组织</li>
 *   <li>查询：普通用户只能查看本组织及全部下级组织的数据，上级组织数据不下沉</li>
 *   <li>管理员（内置 admin 或「超级管理员」角色）不受组织限制，全局可见</li>
 * </ul>
 *
 * <p>新增交易类数据接入步骤：
 * <ol>
 *   <li>实体继承 {@link OrgOwnedEntity}，表增加 org_id 字段（或提供等价组织字段）</li>
 *   <li>仓储继承 {@link OrgScopedRepository}</li>
 *   <li>查询列表时叠加 {@link #scoped(String)} 返回的 Specification</li>
 *   <li>直接 new 实体保存即可自动归属；需要显式指定组织时调用 {@link #writeOrgId()}</li>
 * </ol>
 */
@Service
public class OrgScopeService {

  /**
   * 根节点组织 id（见 data.sql 组织种子数据）。org_id 为空的存量数据按根组织处理。
   */
  public static final long ROOT_ORG_ID = 1L;

  private final UserRepository userRepository;
  private final OrganizationRepository organizationRepository;

  public OrgScopeService(UserRepository userRepository, OrganizationRepository organizationRepository) {
    this.userRepository = userRepository;
    this.organizationRepository = organizationRepository;
  }

  /**
   * 当前请求的登录用户 id。
   */
  public Long currentUserId() {
    return OrgScopeContext.userId();
  }

  /**
   * 当前请求的登录用户组织 id，未分配组织时返回 null。
   */
  public Long currentOrgId() {
    return currentOrgId(currentUserId());
  }

  /**
   * 指定用户的组织 id。优先使用登录上下文，上下文缺失时回查数据库，兼容旧会话。
   */
  @Transactional(readOnly = true)
  public Long currentOrgId(Long userId) {
    if (userId == null) {
      return null;
    }
    OrgScopeContext context = OrgScopeContext.current();
    if (context != null && userId.equals(context.userId()) && context.orgId() != null) {
      return context.orgId();
    }
    return userRepository.findOrgIdByUserId(userId).orElse(null);
  }

  /**
   * 写入数据时应归属的组织 id。
   *
   * <p>管理员未分配组织时落到根组织；普通用户未分配组织则拒绝写入业务数据。
   */
  public Long writeOrgId() {
    Long userId = currentUserId();
    Long orgId = currentOrgId(userId);
    if (orgId != null) {
      return orgId;
    }
    if (isAdmin(userId)) {
      return ROOT_ORG_ID;
    }
    throw new BusinessException(CommonResultStatus.FORBIDDEN, "当前用户未分配组织，无法写入业务数据");
  }

  /**
   * 按组织沉淀的派生数据（对手库、网贷命中、客户聚合）使用的组织。
   *
   * <p>管理员统一使用根组织聚合（根组织子树即全部组织），保证全局可见；
   * 普通用户使用本组织，数据范围为本组织子树。
   */
  public Long dataOrgId(Long userId) {
    if (isAdmin(userId)) {
      return ROOT_ORG_ID;
    }
    return currentOrgId(userId);
  }

  /**
   * 是否管理员：内置 admin 或拥有「超级管理员」角色。
   */
  @Transactional(readOnly = true)
  public boolean isAdmin(Long userId) {
    if (userId == null) {
      return false;
    }
    OrgScopeContext context = OrgScopeContext.current();
    if (context != null && userId.equals(context.userId()) && Boolean.TRUE.equals(context.admin())) {
      return true;
    }
    return userRepository.findById(userId).map(user ->
      "admin".equalsIgnoreCase(user.getUsername())
        || user.getRoles().stream().anyMatch(role -> "超级管理员".equals(role.getName()))
    ).orElse(false);
  }

  /**
   * 指定组织及其全部下级组织 id（含自身）。
   */
  @Transactional(readOnly = true)
  public Set<Long> orgTree(Long orgId) {
    Set<Long> scope = new LinkedHashSet<>();
    if (orgId == null) {
      return scope;
    }
    organizationRepository.findById(orgId).ifPresent(org -> {
      scope.add(org.getId());
      // 子节点 parentIds 以本节点自身路径开头，如 /1/2/ 下所有节点
      String prefix = org.makeSelfAsParentIds();
      for (Organization item : organizationRepository.findAll()) {
        if (item.getId().equals(org.getId())) {
          continue;
        }
        if (item.getParentIds() != null && item.getParentIds().startsWith(prefix)) {
          scope.add(item.getId());
        }
      }
    });
    return scope;
  }

  /**
   * 用户可见的组织 id 集合：管理员为全部组织，普通用户为本组织子树。
   */
  @Transactional(readOnly = true)
  public Set<Long> scopeOrgIds(Long userId) {
    if (userId == null) {
      return Set.of();
    }
    if (isAdmin(userId)) {
      return allOrgIds();
    }
    Long orgId = currentOrgId(userId);
    return orgId == null ? Set.of() : orgTree(orgId);
  }

  /**
   * 当前请求可见的组织 id 集合。
   */
  public Set<Long> scopeOrgIds() {
    return scopeOrgIds(currentUserId());
  }

  /**
   * 判断目标组织是否在当前用户可见范围内（上级组织数据不下沉）。
   */
  public boolean canAccess(Long orgId) {
    long effective = effectiveOrgId(orgId);
    Long userId = currentUserId();
    if (isAdmin(userId)) {
      return true;
    }
    return scopeOrgIds(userId).contains(effective);
  }

  /**
   * 统一的查询可见性 Specification：org_id 落在可见组织集合内，NULL 视为根组织。
   *
   * <p>管理员返回全量条件；无组织用户返回空集条件，避免越权读取。
   */
  public <T> Specification<T> scoped(String orgIdAttribute) {
    Long userId = currentUserId();
    if (isAdmin(userId)) {
      return (root, query, cb) -> cb.conjunction();
    }
    Set<Long> orgIds = scopeOrgIds(userId);
    if (orgIds.isEmpty()) {
      return (root, query, cb) -> cb.disjunction();
    }
    return (root, query, cb) -> cb.coalesce(root.get(orgIdAttribute), ROOT_ORG_ID).in(orgIds);
  }

  /**
   * 严格归属当前组织的 Specification：用于按组织沉淀的派生数据（如对手库、网贷命中）。
   */
  public <T> Specification<T> owned(String orgIdAttribute) {
    Long orgId = dataOrgId(currentUserId());
    if (orgId == null) {
      return (root, query, cb) -> cb.disjunction();
    }
    Long target = orgId;
    return (root, query, cb) -> cb.equal(root.get(orgIdAttribute), target);
  }

  public long effectiveOrgId(Long orgId) {
    return orgId == null ? ROOT_ORG_ID : orgId;
  }

  private Set<Long> allOrgIds() {
    Set<Long> ids = new LinkedHashSet<>();
    organizationRepository.findAll().forEach(org -> ids.add(org.getId()));
    return ids;
  }
}
