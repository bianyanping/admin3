package tech.wetech.admin3.ocrApi;

import tech.wetech.admin3.ocrApi.model.CreditReport;
import tech.wetech.admin3.ocrApi.model.CreditReportItem;
import tech.wetech.admin3.ocrApi.parser.CreditReportNativePdfParser;

import java.io.File;

/** 带完整异常堆栈的验证 */
public class DumpCreditParse {
  public static void main(String[] args) {
    try {
      File pdf = new File(args.length > 0 ? args[0] : "src/main/resources/CREDIT/credit.pdf");
      String pwd = args.length > 1 ? args[1] : "002836";
      System.out.println("Parsing: " + pdf.getAbsolutePath() + " (pwd=" + pwd + ")");

      long t0 = System.currentTimeMillis();
      CreditReport report = CreditReportNativePdfParser.parse(pdf, pwd);
      long t1 = System.currentTimeMillis();
      System.out.println("OK! time=" + (t1 - t0) + "ms");

      System.out.println("reportNo: " + report.getReportNo());
      System.out.println("name: " + report.getName());
      System.out.println("items: " + report.getItems().size());

      long cc = report.getItems().stream().filter(i -> "CREDIT_CARD".equals(i.getItemType())).count();
      long ln = report.getItems().stream().filter(i -> "LOAN".equals(i.getItemType())).count();
      long gu = report.getItems().stream().filter(i -> "GUARANTEE".equals(i.getItemType())).count();
      long oq = report.getItems().stream().filter(i -> "ORG_QUERY".equals(i.getItemType())).count();
      long pq = report.getItems().stream().filter(i -> "PERSON_QUERY".equals(i.getItemType())).count();
      System.out.println("CREDIT_CARD=" + cc + " LOAN=" + ln + " GUARANTEE=" + gu + " ORG_QUERY=" + oq + " PERSON_QUERY=" + pq);

      // 抽样
      System.out.println("\n=== 前 3 条信用卡 ===");
      report.getItems().stream().filter(i -> "CREDIT_CARD".equals(i.getItemType())).limit(3)
        .forEach(i -> System.out.println("  #" + i.getItemNo() + " org=" + i.getOrgName() + " amount=" + i.getAmount() + " used=" + i.getUsedAmount() + " status=" + i.getStatus()));

      System.out.println("\n=== 前 3 条贷款 ===");
      report.getItems().stream().filter(i -> "LOAN".equals(i.getItemType())).limit(3)
        .forEach(i -> System.out.println("  #" + i.getItemNo() + " org=" + i.getOrgName() + " amount=" + i.getAmount() + " balance=" + i.getBalance() + " status=" + i.getStatus() + " open=" + i.getOpenDate()));

      System.out.println("\n=== 前 3 条担保 ===");
      report.getItems().stream().filter(i -> "GUARANTEE".equals(i.getItemType())).limit(3)
        .forEach(i -> System.out.println("  #" + i.getItemNo() + " for=" + i.getGuaranteeCompanyName() + " bank=" + i.getOrgName() + " amount=" + i.getAmount() + " balance=" + i.getBalance() + " contractNo=" + i.getContractNo()));

    } catch (Throwable t) {
      t.printStackTrace();
    }
  }
}
