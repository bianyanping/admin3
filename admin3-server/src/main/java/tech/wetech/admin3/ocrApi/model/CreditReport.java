package tech.wetech.admin3.ocrApi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.sys.model.BaseEntity;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 个人信用报告（整份报告头部 + 概要统计）。
 *
 * <p>一份征信报告对应一条记录，报告编号（reportNo）全局唯一。
 * 明细条目保存在 {@link CreditReportItem}。
 */
@Entity
@Getter
@Setter
@Table(name = "credit_report",
  uniqueConstraints = @UniqueConstraint(name = "uk_credit_report_no", columnNames = "reportNo"),
  indexes = {
    @Index(name = "idx_credit_report_id_no", columnList = "idNo"),
    @Index(name = "idx_credit_report_name", columnList = "name")
  })
@Comment("个人信用报告：一行=一份征信报告的表头与概要统计，判重键=报告编号 report_no")
public class CreditReport extends BaseEntity {

  /**
   * 报告唯一编号，如 "2026102711000513489612"（征信报告右上角的编号）
   */
  @Comment("报告编号（征信报告右上角，全局唯一，判重依据）")
  @Column(length = 64, nullable = false)
  private String reportNo;

  /**
   * 报告生成时间
   */
  @Comment("报告生成时间")
  private LocalDateTime reportTime;

  /**
   * 姓名
   */
  @Comment("被查询人姓名")
  @Column(length = 64)
  private String name;

  /**
   * 证件类型：身份证、护照等
   */
  @Comment("证件类型：身份证/护照等")
  @Column(length = 32)
  private String idType;

  /**
   * 证件号码
   */
  @Comment("证件号码")
  @Column(length = 64)
  private String idNo;

  /**
   * 婚姻状况
   */
  @Comment("婚姻状况：已婚/未婚/未知")
  @Column(length = 16)
  private String maritalStatus;

  // ====== 信息概要统计 ======

  /**
   * 信用卡账户数（含已销户）
   */
  @Comment("信用卡账户数（含已销户，来自信息概要）")
  private Integer creditCardCount;

  /**
   * 未结清/未销户信用卡账户数
   */
  @Comment("未结清/未销户的信用卡账户数")
  private Integer activeCreditCardCount;

  /**
   * 贷款账户数
   */
  @Comment("贷款账户数（购房贷款+其他贷款，含已结清）")
  private Integer loanCount;

  /**
   * 未结清/未销户贷款账户数
   */
  @Comment("未结清/未销户的贷款账户数")
  private Integer activeLoanCount;

  /**
   * 相关还款责任（为企业担保）账户数
   */
  @Comment("相关还款责任账户数（为他人/企业担保）")
  private Integer guaranteeForCompanyCount;

  // ====== 原始追溯 ======

  /**
   * 原始 PDF 路径（类路径相对路径）
   */
  @Comment("原始 PDF 文件路径")
  @Column(length = 256)
  private String rawPdfPath;

  /**
   * 原始 PDFBox 抽取文本，便于追溯和调试
   */
  @Comment("原始 PDF 抽取文本（PDFBox 全文，用于追溯和排查）")
  @Lob
  @Column(columnDefinition = "LONGTEXT")
  private String rawText;

  @Comment("创建人（导入操作人用户名）")
  @Column(length = 64)
  private String createUser;

  @Comment("创建时间（入库时间）")
  private LocalDateTime createTime;

  @Comment("明细条目列表（一对多，指向 credit_report_item.credit_report_id）")
  @OrderBy("itemType, itemNo")
  @OneToMany(mappedBy = "creditReport", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<CreditReportItem> items = new ArrayList<>();

  public void addItem(CreditReportItem item) {
    this.items.add(item);
    item.setCreditReport(this);
  }

  @PrePersist
  protected void onCreate() {
    createTime = LocalDateTime.now();
    UserinfoDTO userInfo = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    if (userInfo != null) {
      createUser = userInfo.username();
    }
  }
}
