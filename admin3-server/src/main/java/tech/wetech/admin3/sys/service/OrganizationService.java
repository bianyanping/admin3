package tech.wetech.admin3.sys.service;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.DomainEventPublisher;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.sys.event.OrganizationCreated;
import tech.wetech.admin3.sys.event.OrganizationDeleted;
import tech.wetech.admin3.sys.event.OrganizationUpdated;
import tech.wetech.admin3.sys.exception.UserException;
import tech.wetech.admin3.sys.model.Organization;
import tech.wetech.admin3.sys.repository.OrganizationRepository;
import tech.wetech.admin3.sys.repository.UserRepository;
import tech.wetech.admin3.sys.service.dto.OrgTreeDTO;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author cjbi
 */
@Repository
public class OrganizationService {

  private final OrganizationRepository organizationRepository;
  private final UserRepository userRepository;

  public OrganizationService(OrganizationRepository organizationRepository, UserRepository userRepository) {
    this.organizationRepository = organizationRepository;
    this.userRepository = userRepository;
  }

  /**
   * 数据权限：当前登录用户只能访问自己所在组织及其下级组织。
   * 请求范围越权时回落到自己所在组织。
   */
  public Organization restrictToCurrentUserScope(Organization requested) {
    Long currentOrgId = currentUserOrgId();
    if (currentOrgId == null || currentOrgId.equals(requested.getId())) {
      return requested;
    }
    Organization currentOrg = findOrganization(currentOrgId);
    if (currentOrg.getParent() == null) {
      return requested;
    }
    String scope = currentOrg.makeSelfAsParentIds();
    if (requested.getParentIds() != null && requested.getParentIds().startsWith(scope)) {
      return requested;
    }
    return currentOrg;
  }

  /**
   * 组织树只展示当前登录用户所在组织及其下级，根节点不再是固定顶层。
   */
  public List<OrgTreeDTO> findOrgTreeInScope(Long parentId) {
    Long currentOrgId = currentUserOrgId();
    if (currentOrgId == null) {
      return List.of();
    }
    Organization currentOrg = findOrganization(currentOrgId);
    return List.of(new OrgTreeDTO(currentOrg));
  }

  private Long currentUserOrgId() {
    UserinfoDTO userInfo = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    if (userInfo == null) {
      return null;
    }
    return userRepository.findOrgIdByUserId(userInfo.userId()).orElse(null);
  }

  public Organization findOrganization(Long id) {
    return organizationRepository.findById(id).orElseThrow(() -> new UserException(CommonResultStatus.RECORD_NOT_EXIST));
  }

  @Transactional
  public Organization createOrganization(String name, Organization.Type type, Long parentId) {
    Organization organization = new Organization();
    organization.setName(name);
    organization.setType(type);
    // 父节点必须在当前用户可见范围内，禁止在越权节点下建组织
    Organization parent = restrictToCurrentUserScope(findOrganization(parentId));
    organization.setParent(parent);
    organization.setParentIds(parent.makeSelfAsParentIds());
    organization = organizationRepository.save(organization);
    DomainEventPublisher.instance().publish(new OrganizationCreated(organization));
    return organization;
  }

  @Transactional
  public Organization updateOrganization(Long id, String name) {
    Organization organization = requireInScope(id);
    organization.setName(name);
    organization = organizationRepository.save(organization);
    DomainEventPublisher.instance().publish(new OrganizationUpdated(organization));
    return organization;
  }

  @Transactional
  public void deleteOrganization(Long id) {
    Organization organization = requireInScope(id);
    organizationRepository.delete(organization);
    DomainEventPublisher.instance().publish(new OrganizationDeleted(organization));
  }

  /**
   * 校验组织节点在当前用户可见范围内，越权直接拒绝。
   */
  private Organization requireInScope(Long id) {
    Organization organization = findOrganization(id);
    if (!restrictToCurrentUserScope(organization).getId().equals(organization.getId())) {
      throw new UserException(CommonResultStatus.FORBIDDEN, "无权操作该组织节点");
    }
    return organization;
  }

  public List<OrgTreeDTO> findOrgTree(Long parentId) {
    return organizationRepository.findByParentId(parentId).stream()
      .map(OrgTreeDTO::new)
      .collect(Collectors.toList());
  }
}
