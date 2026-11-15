package tech.wetech.admin3.ocrApi.service;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.ocrApi.model.CreditReport;
import tech.wetech.admin3.ocrApi.model.CreditReportItem;
import tech.wetech.admin3.ocrApi.repository.CreditReportItemRepository;
import tech.wetech.admin3.ocrApi.repository.CreditReportRepository;

import java.util.Optional;

/**
 * 征信报告入库服务：判重 + 保存。
 *
 * <p>判重规则：按 reportNo（报告编号）全局唯一。
 * 已存在则跳过，不做更新——征信报告是静态快照，同编号=同一份。
 */
@Repository
public class CreditReportService {

  private final CreditReportRepository creditReportRepository;
  private final CreditReportItemRepository creditReportItemRepository;

  public CreditReportService(CreditReportRepository creditReportRepository,
                             CreditReportItemRepository creditReportItemRepository) {
    this.creditReportRepository = creditReportRepository;
    this.creditReportItemRepository = creditReportItemRepository;
  }

  /**
   * 导入征信报告，带判重。
   *
   * @return 保存成功的报告；reportNo 已存在则返回 null（skip）
   */
  @Transactional
  public CreditReport importReport(CreditReport report) {
    Optional<CreditReport> existing = creditReportRepository.findByReportNo(report.getReportNo());
    if (existing.isPresent()) {
      System.out.println("  [判重跳过] 报告编号 " + report.getReportNo()
        + " 已存在（id=" + existing.get().getId() + "）");
      return null;
    }

    // 显式设置双向关联（虽然 addItem 里已经 set 了，但 save 前再走一遍更稳）
    for (CreditReportItem item : report.getItems()) {
      if (item.getCreditReport() == null) item.setCreditReport(report);
    }

    CreditReport saved = creditReportRepository.save(report);
    System.out.println("  [入库成功] 报告编号=" + saved.getReportNo()
      + "，姓名=" + saved.getName()
      + "，证件号=" + saved.getIdNo()
      + "，明细 " + saved.getItems().size() + " 条"
      + "（信用卡=" + count(saved, "CREDIT_CARD")
      + "，贷款=" + count(saved, "LOAN")
      + "，担保=" + count(saved, "GUARANTEE")
      + "，机构查询=" + count(saved, "ORG_QUERY")
      + "，个人查询=" + count(saved, "PERSON_QUERY") + "）");
    return saved;
  }

  private int count(CreditReport r, String type) {
    return (int) r.getItems().stream().filter(i -> type.equals(i.getItemType())).count();
  }

  @Transactional(readOnly = true)
  public Optional<CreditReport> findByReportNo(String reportNo) {
    return creditReportRepository.findByReportNo(reportNo);
  }

  @Transactional(readOnly = true)
  public long countReportItems(Long reportId) {
    return creditReportItemRepository.countByReportId(reportId);
  }

  @Transactional(readOnly = true)
  public long countAll() {
    return creditReportRepository.count();
  }
}
