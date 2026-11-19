package tech.wetech.admin3.sys.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.sys.model.Organization;
import tech.wetech.admin3.sys.model.User;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * @author cjbi
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

  @Query("from User where id in (:userIds)")
  Set<User> findByIds(Set<Long> userIds);

  @Query("""
    from User user where (user.organization=:organization or user.organization.parentIds like concat(:orgParentIds, '%'))
    and (:username is null or user.username=:username)
    and (:state is null or user.state=:state)
    and (:expired is null
      or (:expired = true and user.expireTime is not null and user.expireTime <= :now)
      or (:expired = false and (user.expireTime is null or user.expireTime > :now)))
    """)
  Page<User> findOrgUsers(Pageable pageable, String username, User.State state, Boolean expired,
                          Organization organization, String orgParentIds, LocalDateTime now);

  @Query("select count(user.id) from User user where user.organization=:organization or user.organization.parentIds like concat(:orgParentIds, '%')")
  long countOrgUsers(Organization organization, String orgParentIds);

  @Query("select u.organization.id from User u where u.id = :userId and u.organization is not null")
  java.util.Optional<Long> findOrgIdByUserId(Long userId);

  @Query("select u.organization.name from User u where u.id = :userId and u.organization is not null")
  java.util.Optional<String> findOrgNameByUserId(Long userId);

  java.util.Optional<User> findByWxOpenid(String wxOpenid);

  java.util.Optional<User> findByUsername(String username);

  /**
   * 清理用户与角色的关联（用户删除前调用）。
   */
  @Transactional
  @Modifying
  @Query(value = "delete from user_role where user_id = :userId", nativeQuery = true)
  void deleteUserRoles(Long userId);
}
