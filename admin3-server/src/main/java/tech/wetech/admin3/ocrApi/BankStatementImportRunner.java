package tech.wetech.admin3.ocrApi;

import org.json.JSONArray;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.model.CreditReport;
import tech.wetech.admin3.ocrApi.parser.AbcHxNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.GxrcbNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.NativePdfStatementParser;
import tech.wetech.admin3.ocrApi.parser.PsbcNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.CreditReportNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.IcbcOcrStatementParser;
import tech.wetech.admin3.ocrApi.repository.BankStatementRepository;
import tech.wetech.admin3.ocrApi.service.BankStatementService;
import tech.wetech.admin3.ocrApi.service.CreditReportService;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 样例入库任务：启动时解析原生 PDF 样例并写入数据库，
 * 仅 {@code ocr-import} profile 生效。
 *
 * <p>已开启判重（银行流水按 bankCode+accountNo+startDate+endDate，
 * 征信报告按 reportNo），重复执行不会产生重复数据。
 */
@Component
@Profile("ocr-import")
public class BankStatementImportRunner implements CommandLineRunner {

  private final BankStatementParserFacade parserFacade;
  private final BankStatementService bankStatementService;
  private final BankStatementRepository bankStatementRepository;
  private final CreditReportService creditReportService;

  public BankStatementImportRunner(BankStatementParserFacade parserFacade,
                                   BankStatementService bankStatementService,
                                   BankStatementRepository bankStatementRepository,
                                   CreditReportService creditReportService) {
    this.parserFacade = parserFacade;
    this.bankStatementService = bankStatementService;
    this.bankStatementRepository = bankStatementRepository;
    this.creditReportService = creditReportService;
  }

  @Override
  public void run(String... args) throws IOException {
    System.out.println("========== 银行流水导入 ==========");
    // 中国农业银行（港兴支行）
    importPdfSample("ABCHX", "ABCHX", "港兴农行.pdf", AbcHxNativePdfParser::parse);
    // 邮储银行
    importPdfSample("PSBC", "YZCX", "邮储银行.pdf", PsbcNativePdfParser::parse);
    // 广西农商联合银行
    importPdfSample("GXRCB", "GXRCB", "账户明细查询_20261015134455.pdf", GxrcbNativePdfParser::parse);
    // 桂林国民村镇银行
    importPdfSample("GLGMCZYH", "GLGMCZYH", "本行账户明细下载20261015135120533466.pdf",
      f -> NativePdfStatementParser.parse(f, "GLGMCZYH"));
    // 中国工商银行（扫描件，无文本层，OCR 结果存 JSON 后解析）
    importOcrJsonSample("ICBC", "ICBC", "黄总个人工行半年流水记录表.json");

    System.out.println("\n========== 征信报告导入 ==========");
    importCreditPdfSample("CREDIT", "credit.pdf", "002836");

    System.out.println("\n========== 存储结果 ==========");
    printStoredStatements();
    printStoredCreditReports();
    System.exit(0);
  }

  private void importPdfSample(String bankCode, String dir, String fileName,
                               CheckedParser parser) throws IOException {
    File pdf = resolveClasspathFile(dir, fileName);
    BankStatementDTO dto = parser.parse(pdf);
    BankStatement statement = bankStatementService.importStatement(bankCode, dto, null, null, null, null);
    if (statement == null) return;
    System.out.println("已入库 PDF " + bankCode + " 流水单 id=" + statement.getId()
      + "，明细 " + statement.getTransactionCount() + " 笔"
      + "，账号=" + statement.getAccountNo()
      + "，户名=" + statement.getAccountHolder()
      + "，日期=" + statement.getStartDate() + "~" + statement.getEndDate());
  }

  /**
   * 扫描件银行流水：从已生成的 OCR 表格 JSON 解析入库
   */
  private void importOcrJsonSample(String bankCode, String dir, String fileName) {
    JSONArray pages = OcrSampleLoader.loadFromClasspath(dir, fileName);
    BankStatementDTO dto = IcbcOcrStatementParser.parse(pages);
    BankStatement statement = bankStatementService.importStatement(bankCode, dto, null, null, null, null);
    if (statement == null) return;
    System.out.println("已入库 OCR " + bankCode + " 流水单 id=" + statement.getId()
      + "，明细 " + statement.getTransactionCount() + " 笔"
      + "，账号=" + statement.getAccountNo()
      + "，日期=" + statement.getStartDate() + "~" + statement.getEndDate());
  }

  /**
   * 征信报告带密码的导入入口
   */
  private void importCreditPdfSample(String dir, String fileName, String password) throws IOException {
    File pdf = resolveClasspathFile(dir, fileName);
    System.out.println("解析征信 PDF（密码=" + password + "）: " + pdf.getAbsolutePath());
    CreditReport report = CreditReportNativePdfParser.parse(pdf, password);
    if (report == null) {
      System.out.println("  解析失败！");
      return;
    }
    CreditReport saved = creditReportService.importReport(report);
    if (saved == null) return;  // 判重跳过
  }

  @FunctionalInterface
  private interface CheckedParser {
    BankStatementDTO parse(File file) throws IOException;
  }

  private File resolveClasspathFile(String dir, String fileName) throws IOException {
    String path = dir + "/" + fileName;
    URL url = OcrSampleLoader.class.getClassLoader().getResource(path);
    if (url == null) {
      throw new IOException("找不到资源: " + path);
    }
    return new File(URLDecoder.decode(url.getFile(), StandardCharsets.UTF_8));
  }

  private void printStoredStatements() {
    System.out.println("===== 数据库流水单 =====");
    for (BankStatement statement : bankStatementRepository.findAll()) {
      System.out.println(statement.getId() + " | " + statement.getBankCode() + " | "
        + statement.getBankName() + " | 账号 " + statement.getAccountNo()
        + " | 户名 " + statement.getAccountHolder()
        + " | 日期 " + statement.getStartDate() + "~" + statement.getEndDate()
        + " | 收入 " + statement.getTotalIncome() + "/" + statement.getIncomeCount()
        + " 笔 | 支出 " + statement.getTotalExpense() + "/" + statement.getExpenseCount()
        + " 笔 | 明细 " + statement.getTransactionCount() + " 笔 | 库中明细行数 "
        + bankStatementRepository.countTransactions(statement.getId()));
    }
  }

  private void printStoredCreditReports() {
    System.out.println("===== 数据库征信报告 =====");
    long total = creditReportService.countAll();
    System.out.println("报告总数: " + total);
    if (total > 0) {
      creditReportService.findByReportNo("2026102711000513489612").ifPresent(r -> {
        System.out.println("报告编号: " + r.getReportNo());
        System.out.println("报告时间: " + r.getReportTime());
        System.out.println("姓名: " + r.getName() + " | 证件: " + r.getIdType() + "=" + r.getIdNo()
          + " | 婚姻: " + r.getMaritalStatus());
        System.out.println("概要: 信用卡=" + r.getCreditCardCount() + " (未结清=" + r.getActiveCreditCardCount()
          + ") 贷款=" + r.getLoanCount() + " (未结清=" + r.getActiveLoanCount()
          + ") 为企业担保=" + r.getGuaranteeForCompanyCount());
        System.out.println("明细条数: " + creditReportService.countReportItems(r.getId()));
      });
    }
  }
}
