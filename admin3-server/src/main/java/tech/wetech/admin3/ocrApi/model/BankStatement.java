package tech.wetech.admin3.ocrApi.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.sys.model.BaseEntity;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 银行流水单（整份对账单）。
 *
 * <p>一条记录对应一次 OCR 解析结果的表头 + 汇总信息，
 * 交易明细保存在 {@link BankStatementTransaction}。
 */
@Entity
@Getter
@Setter
@Table(name = "bank_statement",
  uniqueConstraints = @UniqueConstraint(
    name = "uk_bank_statement_identity",
    columnNames = {"bankCode", "accountNo", "startDate", "endDate"}),
  indexes = {
    @Index(name = "idx_bank_statement_bank_code", columnList = "bankCode"),
    @Index(name = "idx_bank_statement_account_no", columnList = "accountNo"),
    // 组织范围过滤
    @Index(name = "idx_bank_statement_org_id", columnList = "orgId")
  })
@Comment("银行流水单：一条记录=某银行某账号某账期的一份对账单解析结果，判重键=银行编码+账号+起止日期")
public class BankStatement extends BaseEntity {

  /**
   * 银行编码，如 PSBC、GXRCB
   */
  @Comment("银行编码：PSBC=邮储银行 GXRCB=广西农商联合银行 ABCHX=农行 GLYH=桂林银行 GLGMCZYH=桂林国民村镇银行")
  @Column(length = 32)
  private String bankCode;

  /**
   * 银行名称
   */
  @Comment("银行名称（PDF 中读取到的全称）")
  @Column(length = 128)
  private String bankName;

  /**
   * 账号/卡号
   */
  @Comment("账号/卡号")
  @Column(length = 64)
  private String accountNo;

  /**
   * 户名
   */
  @Comment("账户户名")
  @Column(length = 128)
  private String accountHolder;

  /**
   * 流水起止日期
   */
  @Comment("流水起始日期（账期开始）")
  private LocalDate startDate;

  @Comment("流水结束日期（账期结束）")
  private LocalDate endDate;

  @Comment("币种，如人民币/美元")
  @Column(length = 16)
  private String currency;

  /**
   * 支出合计
   */
  @Comment("支出合计金额")
  @Column(precision = 18, scale = 2)
  private BigDecimal totalExpense;

  /**
   * 支出笔数
   */
  @Comment("支出笔数")
  private Integer expenseCount;

  /**
   * 收入合计
   */
  @Comment("收入合计金额")
  @Column(precision = 18, scale = 2)
  private BigDecimal totalIncome;

  /**
   * 收入笔数
   */
  @Comment("收入笔数")
  private Integer incomeCount;

  /**
   * 明细申请/导出时间
   */
  @Comment("流水申请/导出时间（PDF 显示的查询时间）")
  @Column(length = 64)
  private String applyTime;

  /**
   * 明细笔数
   */
  @Comment("实际入库的交易明细笔数")
  private Integer transactionCount;

  /**
   * 原始 OCR 文本，便于追溯
   */
  @Comment("原始解析文本（PDFBox 抽取的全文本，用于追溯和排查）")
  @JsonIgnore
  @Lob
  @Column(columnDefinition = "LONGTEXT")
  private String rawOcrText;

  @Comment("上传的原始文件名（含扩展名）")
  @Column(length = 256)
  private String sourceFileName;

  @Comment("原始文件在对象存储中的 key，用于下载原文件")
  @Column(length = 256)
  private String sourceFileKey;

  @Comment("创建人（导入操作人用户名）")
  @Column(length = 64)
  private String createUser;

  @Comment("创建时间（入库时间）")
  private LocalDateTime createTime;

  /**
   * 数据归属组织：导入人所属组织节点，数据隔离依据。
   * NULL 视为根节点数据（仅根节点用户可见，或被分享）。
   */
  @Comment("数据归属组织 id（导入人所属组织），数据隔离依据；NULL 视为根节点数据")
  @Column(name = "org_id")
  private Long orgId;

  @Comment("数据归属组织名称快照")
  @Column(length = 128)
  private String orgName;

  @Comment("交易明细列表（一对多，指向 bank_statement_transaction.statement_id）")
  @JsonIgnore
  @OrderBy("seqNo")
  @OneToMany(mappedBy = "statement", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<BankStatementTransaction> transactions = new ArrayList<>();

  public void addTransaction(BankStatementTransaction transaction) {
    // 先加入集合再设置 owning side：Hibernate 字节码增强会自动校验反向集合，
    // 若先 setStatement 会被增强逻辑自动加入一次，导致手动添加时出现重复明细。
    this.transactions.add(transaction);
    transaction.setStatement(this);
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
