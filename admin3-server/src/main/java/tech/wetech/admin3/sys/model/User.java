package tech.wetech.admin3.sys.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static jakarta.persistence.FetchType.LAZY;

/**
 * 用户
 *
 * @author cjbi
 */
@Entity
public class User extends BaseEntity {

  @Column(nullable = false, unique = true)
  private String username;

  private String avatar;

  /**
   * 个人简介，新建用户时从励志语池随机初始化，用户可自行修改
   */
  @Column(length = 255)
  private String intro;

  /**
   * 微信小程序 openid，绑定后小程序登录即以此身份继承所属组织的可见范围
   */
  @Column(length = 64, unique = true)
  private String wxOpenid;

  @Column(nullable = false)
  private Gender gender;

  @Column(nullable = false)
  private State state;

  @Column
  private LocalDateTime createdTime;

  /**
   * 账号过期时间，为空表示永不过期
   */
  @Column
  private LocalDateTime expireTime;

  @ManyToMany(fetch = LAZY, cascade = CascadeType.DETACH)
  @JoinTable(name = "user_role",
    joinColumns = @JoinColumn(name = "user_id", referencedColumnName = "id"),
    inverseJoinColumns = @JoinColumn(name = "role_id", referencedColumnName = "id"))
  private Set<Role> roles = new LinkedHashSet<>();

  @OneToMany(cascade = CascadeType.ALL, mappedBy = "id", orphanRemoval = true)
  private Set<UserCredential> credentials = new LinkedHashSet<>();

  @ManyToOne(fetch = LAZY)
  private Organization organization;

  public String getOrgFullName() {
    return concatOrgName(getOrganization());
  }

  private String concatOrgName(Organization org) {
    if (org.getParent() != null) {
      return concatOrgName(org.getParent()).concat("-").concat(org.getName());
    }
    return org.getName();
  }

  @PrePersist
  protected void onCreate() {
    this.createdTime = LocalDateTime.now();
  }

  /**
   * 获取用户权限列表
   *
   * @return
   */
  public Set<String> findPermissions() {
    Set<String> permissions = roles.stream()
      .map(Role::getResources)
      .flatMap(Collection::stream)
      .map(Resource::getPermission)
      .collect(Collectors.toSet());
    // admin 为内置超级管理员，不受角色/权限配置影响
    if ("admin".equalsIgnoreCase(username)) {
      permissions.add("*");
    }
    return permissions;
  }

  public enum Gender {
    MALE, FEMALE
  }

  public enum State {
    NORMAL, LOCKED
  }

  public boolean isLocked() {
    return this.state == State.LOCKED;
  }


  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getAvatar() {
    return avatar;
  }

  public void setAvatar(String avatar) {
    this.avatar = avatar;
  }

  public String getIntro() {
    return intro;
  }

  public void setIntro(String intro) {
    this.intro = intro;
  }

  public String getWxOpenid() {
    return wxOpenid;
  }

  public void setWxOpenid(String wxOpenid) {
    this.wxOpenid = wxOpenid;
  }

  public Gender getGender() {
    return gender;
  }

  public void setGender(Gender gender) {
    this.gender = gender;
  }

  public State getState() {
    return state;
  }

  public void setState(State state) {
    this.state = state;
  }

  public LocalDateTime getCreatedTime() {
    return createdTime;
  }

  public void setCreatedTime(LocalDateTime createdTime) {
    this.createdTime = createdTime;
  }

  public LocalDateTime getExpireTime() {
    return expireTime;
  }

  public void setExpireTime(LocalDateTime expireTime) {
    this.expireTime = expireTime;
  }

  public Set<Role> getRoles() {
    return roles;
  }

  public void setRoles(Set<Role> roles) {
    this.roles = roles;
  }

  public Set<UserCredential> getCredentials() {
    return credentials;
  }

  public void setCredentials(Set<UserCredential> credentials) {
    this.credentials = credentials;
  }

  public Organization getOrganization() {
    return organization;
  }

  public void setOrganization(Organization organization) {
    this.organization = organization;
  }
}
