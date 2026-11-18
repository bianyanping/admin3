package tech.wetech.admin3.sys.service;

import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.common.*;
import tech.wetech.admin3.sys.event.UserCreated;
import tech.wetech.admin3.sys.event.UserDeleted;
import tech.wetech.admin3.sys.event.UserUpdated;
import tech.wetech.admin3.sys.exception.UserException;
import tech.wetech.admin3.sys.model.Organization;
import tech.wetech.admin3.sys.model.User;
import tech.wetech.admin3.sys.model.UserCredential;
import tech.wetech.admin3.sys.repository.SessionRepository;
import tech.wetech.admin3.sys.repository.StoredEventRepository;
import tech.wetech.admin3.sys.repository.UserCredentialRepository;
import tech.wetech.admin3.sys.repository.UserRepository;
import tech.wetech.admin3.sys.service.dto.OrgUserDTO;
import tech.wetech.admin3.sys.service.dto.PageDTO;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static tech.wetech.admin3.common.CommonResultStatus.RECORD_NOT_EXIST;

/**
 * @author cjbi
 */
@Service
public class UserService {

  /**
   * 新建用户的默认密码
   */
  public static final String DEFAULT_PASSWORD = "123456";

  private final UserRepository userRepository;
  private final UserCredentialRepository userCredentialRepository;
  private final StoredEventRepository storedEventRepository;
  private final SessionRepository sessionRepository;

  public UserService(UserRepository userRepository, UserCredentialRepository userCredentialRepository,
                     StoredEventRepository storedEventRepository, SessionRepository sessionRepository) {
    this.userRepository = userRepository;
    this.userCredentialRepository = userCredentialRepository;
    this.storedEventRepository = storedEventRepository;
    this.sessionRepository = sessionRepository;
  }

  @Transactional
  public User createUser(String username, String avatar, User.Gender gender, User.State state, Organization organization) {
    User user = new User();
    user.setUsername(username);
    user.setAvatar(avatar);
    user.setIntro(InspirationalQuotes.random());
    user.setGender(gender);
    user.setState(state);
    user.setCreatedTime(LocalDateTime.now());
    user.setOrganization(organization);
    user = userRepository.save(user);
    // 创建默认密码凭证
    saveCredential(user, DEFAULT_PASSWORD);
    DomainEventPublisher.instance().publish(new UserCreated(user));
    return user;
  }

  /**
   * 保存用户密码凭证（MD5(username + password)）
   */
  private void saveCredential(User user, String password) {
    try {
      UserCredential credential = new UserCredential();
      credential.setIdentifier(user.getUsername());
      credential.setCredential(SecurityUtil.md5(user.getUsername(), password));
      credential.setIdentityType(UserCredential.IdentityType.PASSWORD);
      credential.setUser(user);
      userCredentialRepository.save(credential);
    } catch (Exception e) {
      throw new UserException(CommonResultStatus.FAIL, "密码加密失败：" + e.getMessage());
    }
  }

  public Set<User> findUserByIds(Set<Long> userIds) {
    return userRepository.findByIds(userIds);
  }

  public User findUserById(Long userId) {
    return userRepository.findById(userId)
      .orElseThrow(() -> new BusinessException(RECORD_NOT_EXIST));
  }

  public PageDTO<OrgUserDTO> findOrgUsers(Pageable pageable, String username, User.State state, Organization organization) {
    Page<User> page = userRepository.findOrgUsers(pageable, username, state, organization, organization.makeSelfAsParentIds());
    return new PageDTO<>(page.getContent().stream().map(u ->
        new OrgUserDTO(u.getId(), u.getUsername(), u.getAvatar(), u.getGender(), u.getState(), u.getOrgFullName(), u.getCreatedTime()))
      .collect(Collectors.toList()), page.getTotalElements());
  }

  public boolean existsUsers(Organization organization) {
    String orgParentIds = organization.makeSelfAsParentIds();
    return userRepository.countOrgUsers(organization, orgParentIds) > 0;
  }


  @Transactional
  public User updateUser(Long userId, String avatar, User.Gender gender, User.State state, Organization organization) {
    User user = findUserById(userId);
    user.setAvatar(avatar);
    user.setGender(gender);
    user.setState(state);
    user.setOrganization(organization);
    user = userRepository.save(user);
    DomainEventPublisher.instance().publish(new UserUpdated(user));
    return user;
  }

  @Transactional
  public User updateIntro(Long userId, String intro) {
    User user = findUserById(userId);
    user.setIntro(intro == null || intro.isBlank() ? null : intro.trim());
    return userRepository.save(user);
  }

  @Transactional
  public User disableUser(Long userId) {
    UserinfoDTO userInfo = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    if (Objects.equals(userInfo.userId(), userId)) {
      throw new UserException(CommonResultStatus.PARAM_ERROR, "不能禁用自己");
    }
    User user = findUserById(userId);
    user.setState(User.State.LOCKED);
    return userRepository.save(user);
  }

  @Transactional
  public User enableUser(Long userId) {
    User user = findUserById(userId);
    user.setState(User.State.NORMAL);
    return userRepository.save(user);
  }

  public PageDTO<User> findUsers(Pageable pageable, User user) {
    Page<User> page = userRepository.findAll(Example.of(user), pageable);
    return new PageDTO<>(page.getContent(), page.getTotalElements());
  }

  @Transactional
  public void delete(Long userId) {
    User user = findUserById(userId);
    // 审计日志保留，仅解除与待删除用户的关联
    storedEventRepository.detachUser(userId);
    // 依次清理登录会话、登录凭证、角色关联，最后删除用户
    sessionRepository.deleteByUserId(userId);
    userCredentialRepository.deleteByUserId(userId);
    userRepository.deleteUserRoles(userId);
    userRepository.delete(user);
    DomainEventPublisher.instance().publish(new UserDeleted(user));
  }

  /**
   * 重置用户密码为默认密码 123456
   */
  @Transactional
  public void resetPassword(Long userId) {
    User user = findUserById(userId);
    UserCredential credential = userCredentialRepository
      .findCredential(user.getUsername(), UserCredential.IdentityType.PASSWORD)
      .orElseGet(() -> {
        UserCredential c = new UserCredential();
        c.setIdentifier(user.getUsername());
        c.setIdentityType(UserCredential.IdentityType.PASSWORD);
        c.setUser(user);
        return c;
      });
    try {
      credential.setCredential(SecurityUtil.md5(user.getUsername(), DEFAULT_PASSWORD));
      userCredentialRepository.save(credential);
    } catch (Exception e) {
      throw new UserException(CommonResultStatus.FAIL, "密码加密失败：" + e.getMessage());
    }
  }

  /**
   * 用户自助修改密码（需校验旧密码）
   */
  @Transactional
  public void changePassword(Long userId, String oldPassword, String newPassword) {
    User user = findUserById(userId);
    UserCredential credential = userCredentialRepository
      .findCredential(user.getUsername(), UserCredential.IdentityType.PASSWORD)
      .orElseThrow(() -> new UserException(CommonResultStatus.RECORD_NOT_EXIST, "用户凭证不存在"));
    if (!credential.doCredentialMatch(oldPassword)) {
      throw new UserException(CommonResultStatus.PARAM_ERROR, "旧密码不正确");
    }
    try {
      credential.setCredential(SecurityUtil.md5(user.getUsername(), newPassword));
      userCredentialRepository.save(credential);
    } catch (Exception e) {
      throw new UserException(CommonResultStatus.FAIL, "密码加密失败：" + e.getMessage());
    }
  }
}
