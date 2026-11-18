package tech.wetech.admin3.ocrApi.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.sys.model.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 个人信用报告明细项。
 *
 * <p>混合承载四类条目：信用卡(CREDIT_CARD)、贷款(LOAN)、
 * 担保(GUARANTEE)、查询记录(ORG_QUERY / PERSON_QUERY)。
 * 不同 itemType 使用各自相关字段，其余为 null。
 */
@Entity
@Getter
@Setter
@Table(name = "credit_report_item",
  uniqueConstraints = @UniqueConstraint(
    name = "uk_credit_report_item",
    columnNames = {"creditReport_id", "item_type", "item_no", "org_name"}),
  indexes = {
    @Index(name = "idx_cri_report_id", columnList = "creditReport_id"),
    @Index(name = "idx_cri_type", columnList = "item_type"),
    @Index(name = "idx_cri_org", columnList = "org_name")
  })
@Comment("征信报告明细：混合存放信用卡/贷款/担保/查询记录，用 item_type 区分，各类型只填自己的字段")
public class CreditReportItem extends BaseEntity {

  @Comment("所属征信报告（credit_report.id）")
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "creditReport_id")
  private CreditReport creditReport;

  /**
   * 条目类型：CREDIT_CARD / LOAN / GUARANTEE / ORG_QUERY / PERSON_QUERY
   */
  @Comment("条目类型：CREDIT_CARD=信用卡 LOAN=贷款 GUARANTEE=担保 ORG_QUERY=机构查询 PERSON_QUERY=个人查询")
  @Column(length = 32)
  private String itemType;

  /**
   * 报告内的序号（1-based）
   */
  @Comment("报告内序号（1 起，对应 PDF 中的序号 1、2…）")
  private Integer itemNo;

  // ====== 机构 / 银行名（四类条目通用）======
  @Comment("机构/银行名称（放款机构、发卡行、担保权人、查询机构）")
  @Column(length = 256)
  private String orgName;

  // ====== 发放 / 授信 / 查询日期 ======
  @Comment("发放/授信/担保日期（信用卡、贷款、担保使用）")
  private LocalDate openDate;

  @Comment("查询日期（仅 ORG_QUERY / PERSON_QUERY 使用）")
  private LocalDate queryDate;

  @Comment("到期日期（贷款：到期日；信用卡销户：销户月份；担保：额度有效期）")
  private LocalDate expiryDate;

  // ====== 合同 / 卡片信息 ======
  @Comment("保证合同编号（仅 GUARANTEE 使用）")
  @Column(length = 512)
  private String contractNo;

  @Comment("卡片尾号（仅 CREDIT_CARD 使用）")
  @Column(length = 32)
  private String tailNo;

  @Comment("账号（信用卡/贷款，如有）")
  @Column(length = 64)
  private String accountNo;

  // ====== 产品类型 ======
  @Comment("产品类型：贷记卡/个人经营性贷款/个人住房商业贷款/其他个人消费贷款等")
  @Column(length = 128)
  private String productType;

  @Comment("币种：人民币/美元")
  @Column(length = 16)
  private String currency;

  // ====== 金额 ======
  @Comment("金额：信用卡=信用额度 贷款=发放金额 担保=相关还款责任金额")
  @Column(precision = 18, scale = 2)
  private BigDecimal amount;

  @Comment("已使用额度（仅 CREDIT_CARD 使用）")
  @Column(precision = 18, scale = 2)
  private BigDecimal usedAmount;

  @Comment("当前余额（贷款/担保使用）")
  @Column(precision = 18, scale = 2)
  private BigDecimal balance;

  // ====== 状态 ======
  @Comment("账户状态：未结清/已结清/已销户/可循环使用/当前无逾期等")
  @Column(length = 64)
  private String status;

  // ====== GUARANTEE 专用：被担保企业 ======
  @Comment("被担保企业名称（仅 GUARANTEE 使用）")
  @Column(length = 512)
  private String guaranteeCompanyName;

  @Comment("被担保企业的证件类型：中征码/统一社会信用代码（仅 GUARANTEE 使用）")
  @Column(length = 64)
  private String guaranteeCompanyIdType;

  @Comment("被担保企业的证件号码/中征码（仅 GUARANTEE 使用）")
  @Column(length = 512)
  private String guaranteeCompanyIdNo;

  @Comment("责任人类型：保证人/抵押人等（仅 GUARANTEE 使用）")
  @Column(length = 16)
  private String responsibilityType;

  // ====== QUERY 专用 ======
  @Comment("查询原因：贷后管理/贷款审批/担保资格审查/信用卡审批/本人查询等（仅 QUERY 使用）")
  @Column(length = 128)
  private String queryReason;

  @Comment("查询主体：本人/机构（仅 QUERY 使用）")
  @Column(length = 64)
  private String querySubject;

  // ====== 原始句子 ======
  @Comment("PDF 中该条目的原始完整句子，用于追溯核对")
  @Column(length = 2000)
  private String rawSentence;
}
