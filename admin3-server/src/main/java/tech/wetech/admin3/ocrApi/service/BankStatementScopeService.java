package tech.wetech.admin3.ocrApi.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.repository.BankStatementShareRepository;
import tech.wetech.admin3.sys.model.Organization;
import tech.wetech.admin3.sys.model.User;
import tech.wetech.admin3.sys.repository.OrganizationRepository;
import tech.wetech.admin3.sys.repository.UserRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 银行流水数据隔离与共享的范围计算。
 *
 * <p>规则：
 * <ul>
 *   <li>每条流水单归属导入人所在组织节点（org_id），NULL 视为根节点数据</li>
 *   <li>用户可见范围 = 自己组织节点的子树（含自身）∪ 被分享给自己的流水单</li>
 *   <li>根节点用户自然可见全部数据</li>
 * </ul>
 */
@Service
public class BankStatementScopeService {

  /**
   * 根节点组织 id（见 data.sql 组织种子数据）
   */
  public static final long ROOT_ORG_ID = 1L;

  private final UserRepository userRepository;
  private final OrganizationRepository organizationRepository;
  private final BankStatementShareRepository shareRepository;

  public BankStatementScopeService(UserRepository userRepository,
                                   OrganizationRepository organizationRepository,
                                   BankStatementShareRepository shareRepository) {
    this.userRepository = userRepository;
    this.organizationRepository = organizationRepository;
    this.shareRepository = shareRepository;
  }

  /**
   * 用户可见的组织 id 集合（所属节点子树，含自身）。
   */
  @Transactional(readOnly = true)
  public Set<Long> scopeOrgIds(Long userId) {
    Set<Long> scope = new HashSet<>();
    userRepository.findById(userId).ifPresent(user -> {
      Organization org = user.getOrganization();
      if (org == null) {
        return;
      }
      // 根组织（无上级）可见全部数据
      if (org.getParent() == null) {
        organizationRepository.findAll().forEach(o -> scope.add(o.getId()));
        return;
      }
      scope.add(org.getId());
      // 子孙节点：parentIds 以「本节点自身路径」开头，如本节点为 总部/财务部 时
      // 前缀为 .../2/，会计组、出纳组的 parentIds 均以其开头
      String prefix = org.makeSelfAsParentIds();
      for (Organization o : organizationRepository.findAll()) {
        if (o.getParentIds() != null && o.getParentIds().startsWith(prefix)) {
          scope.add(o.getId());
        }
      }
    });
    return scope;
  }

  /**
   * 判断用户是否可见该流水单：组织子树内 或 被分享给该用户。
   */
  @Transactional(readOnly = true)
  public boolean visible(Long userId, BankStatement statement) {
    Set<Long> scope = scopeOrgIds(userId);
    Long effOrgId = statement.getOrgId() == null ? ROOT_ORG_ID : statement.getOrgId();
    if (scope.contains(effOrgId)) {
      return true;
    }
    return shareRepository.existsByStatementIdAndGranteeUserId(statement.getId(), userId);
  }

  /**
   * 不可见时抛出业务异常。
   */
  public void requireVisible(Long userId, BankStatement statement) {
    if (!visible(userId, statement)) {
      throw new BusinessException(CommonResultStatus.FAIL, "无权访问该流水单");
    }
  }

  /**
   * 流水单列表的可见性过滤条件（orgId 归属子树内 OR 被分享给用户）。
   */
  @Transactional(readOnly = true)
  public Visibility visibilityOf(Long userId) {
    Set<Long> orgIds = scopeOrgIds(userId);
    List<Long> sharedIds = shareRepository.findStatementIdsByGranteeUserId(userId);
    return new Visibility(orgIds, sharedIds);
  }

  public record Visibility(Set<Long> orgIds, List<Long> sharedIds) {
    public boolean nothing() {
      return orgIds.isEmpty() && sharedIds.isEmpty();
    }
  }

  /**
   * 可选的分享目标用户列表（含组织全路径，供分享弹窗选择）。
   */
  @Transactional(readOnly = true)
  public List<ShareUser> listShareUsers() {
    List<ShareUser> list = new ArrayList<>();
    for (User u : userRepository.findAll()) {
      list.add(new ShareUser(u.getId(), u.getUsername(), u.getOrgFullName()));
    }
    return list;
  }

  public record ShareUser(Long id, String username, String orgFullName) {
  }
}
