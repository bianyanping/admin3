package tech.wetech.admin3.sys.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.sys.model.StoredEvent;

import java.util.Set;

/**
 * @author cjbi
 */
@Repository
public interface StoredEventRepository extends JpaRepository<StoredEvent, Long> {

  Page<StoredEvent> findByTypeNameInOrderByOccurredOnDesc(Set<String> typeNames, Pageable pageable);

  /**
   * 删除用户前解除审计日志与用户的关联，保留日志本身。
   */
  @Transactional
  @Modifying
  @Query("update StoredEvent e set e.user = null where e.user.id = :userId")
  void detachUser(Long userId);

}
