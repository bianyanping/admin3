package tech.wetech.admin3.ocrApi.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

import java.time.LocalDateTime;

/**
 * 流水单数据共享记录：把某条流水单分享给某个用户查看。
 */
@Entity
@Getter
@Setter
@Table(name = "bank_statement_share",
  indexes = {
    @Index(name = "idx_bs_share_statement", columnList = "statementId"),
    @Index(name = "idx_bs_share_grantee", columnList = "granteeUserId")
  })
@Comment("银行流水单共享记录：被分享用户可在数据隔离范围之外查看该流水单")
public class BankStatementShare extends BaseEntity {

  @Comment("被分享的流水单 id")
  @Column(name = "statement_id", nullable = false)
  private Long statementId;

  @Comment("被分享用户 id")
  @Column(name = "grantee_user_id", nullable = false)
  private Long granteeUserId;

  @Comment("被分享用户名快照")
  @Column(length = 64)
  private String granteeName;

  @Comment("分享操作人 id")
  @Column(name = "granted_by_user_id")
  private Long grantedByUserId;

  @Comment("分享操作人用户名快照")
  @Column(length = 64)
  private String grantedByName;

  @Comment("分享时间")
  private LocalDateTime createTime;
}
