package tech.wetech.admin3.ocrApi.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.AmountNormalizer;
import tech.wetech.admin3.ocrApi.layout.PdfColumnTableReader;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广西农商联合银行原生 PDF 解析器（版式：账户明细查询）。
 *
 * <p>版式特征：
 * <pre>
 *   账户明细查询
 *   账号/户名：376512010107070880 广西达远科技有限公司 起止日期：2025-10-01至2025-12-31
 *   序号 交易时间 支出/收入金额 账户余额 对方账号 对方户名 交易渠道 用途 摘要 摘要内容 附言 备注
 *   1 2025-10-01 -600,000.00 2,191,066.18 ...
 * </pre>
 *
 * <p>金额自带正负号（− 支出，+ 收入），不需要再用余额差反推方向。
 *
 * <p>页眉正则特意用宽松写法：pdfbox 抽出来的"户"字 Unicode（21345）
 * 可能和源码里写的（25143）不一致，所以改成基于数字/日期序列定位。
 */
public final class GxrcbNativePdfParser {

  /** 账号：第一个 15+ 位数字 */
  private static final Pattern ACCOUNT_NUM = Pattern.compile("\\d{15,}");
  /** 户名：账号后紧跟的连续中/英/括号/冒号段，匹配完再 trim 掉尾部的全角/半角冒号 */
  private static final Pattern NAME_AFTER_ACCOUNT = Pattern.compile(
    "\\s*([\\u4e00-\\u9fa5A-Za-z（）():：]+)");
  /** 日期 */
  private static final Pattern ANY_DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");

  private static final Pattern INCOME_SUM = Pattern.compile("收入合计[：:]*\\s*([\\d,.]+)");
  private static final Pattern INCOME_COUNT = Pattern.compile("收入笔数[：:]*\\s*(\\d+)");
  private static final Pattern EXPENSE_SUM = Pattern.compile("支出合计[：:]*\\s*([\\d,.]+)");
  private static final Pattern EXPENSE_COUNT = Pattern.compile("支出笔数[：:]*\\s*(\\d+)");
  private static final Pattern APPLY_TIME = Pattern.compile("明细申请时间[：:]*\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})");

  /** 账号/户名等列会折行，必须按坐标还原整行 */
  private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
  static {
    COLUMNS.put("序号", "seqNo");
    COLUMNS.put("交易时间", "transTime");
    COLUMNS.put("支出/收入金额", "amount");
    COLUMNS.put("账户余额", "balance");
    COLUMNS.put("对方账号", "counterpartyAccount");
    COLUMNS.put("对方户名", "counterpartyName");
    COLUMNS.put("交易渠道", "channel");
    COLUMNS.put("用途", "purpose");
    COLUMNS.put("摘要", "summary");
    COLUMNS.put("摘要内容", "summaryContent");
    COLUMNS.put("附言", "postscript");
    COLUMNS.put("备注", "remark");
  }
  private static final Set<String> REQUIRED_COLUMNS = Set.of(
    "transTime", "amount", "balance", "counterpartyAccount", "counterpartyName");
  private static final Pattern ROW_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private GxrcbNativePdfParser() {}

  public static BankStatementDTO parse(File pdfFile) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    header.setBankName("广西农商联合银行");
    header.setCurrency("人民币");

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);

      int totalPages = doc.getNumberOfPages();
      String firstPage = null;
      String lastPage = null;
      for (int p = 1; p <= totalPages; p++) {
        stripper.setStartPage(p);
        stripper.setEndPage(p);
        String pageText = stripper.getText(doc);
        raw.append(pageText).append('\n');
        if (p == 1) firstPage = pageText;
        if (p == totalPages) lastPage = pageText;
      }

      if (firstPage != null) {
        for (String line : firstPage.split("\\r?\\n")) {
          String trimmed = line.trim();
          if (!trimmed.isEmpty()) fillHeaderOnce(trimmed, header);
        }
      }

      // 账号/户名/用途等列会折行，按坐标取完整单元格
      for (PdfColumnTableReader.Row row : PdfColumnTableReader.read(
        doc, COLUMNS, REQUIRED_COLUMNS, "transTime", ROW_DATE)) {
        TransactionDTO tx = buildTransaction(row);
        if (tx != null) transactions.add(tx);
      }

      if (lastPage != null) collectSummary(lastPage, summary);
    }

    // 兜底：末页没抽到汇总时，用明细反算
    if (summary.getTotalIncome() == null || summary.getTotalExpense() == null) {
      BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
      int ic = 0, ec = 0;
      for (TransactionDTO tx : transactions) {
        BigDecimal a = tx.getAmount();
        if (a == null || a.signum() == 0) continue;
        if (a.signum() >= 0) { income = income.add(a); ic++; }
        else { expense = expense.add(a.negate()); ec++; }
      }
      if (summary.getTotalIncome() == null) { summary.setTotalIncome(income); summary.setIncomeCount(ic); }
      if (summary.getTotalExpense() == null) { summary.setTotalExpense(expense); summary.setExpenseCount(ec); }
    }

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  /** 只在第一次调用时填充（header == null 的字段才填），避免续页页眉覆盖 */
  private static void fillHeaderOnce(String line, StatementHeader header) {
    if (header.getAccountNo() == null) {
      Matcher m = ACCOUNT_NUM.matcher(line);
      if (m.find()) {
        header.setAccountNo(m.group());

        // 户名 = 账号数字之后 → 下一个"日期标签 + YYYY-MM-DD" 之前的所有内容
        int afterAccount = m.end();
        String tail = line.substring(afterAccount);
        // 用日期 regex 找第一个日期起始位置，往前跳过"起止日期"或"日期"标签（2~4 字）
        Matcher endMarker = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(tail);
        int endPos = tail.length();
        if (endMarker.find()) {
          int dateStart = endMarker.start();
          // 往前找最近的中文冒号或空白作为标签和日期的边界
          int labelEnd = dateStart;
          while (labelEnd > 0 && (tail.charAt(labelEnd - 1) == '：'
            || tail.charAt(labelEnd - 1) == ':'
            || Character.isWhitespace(tail.charAt(labelEnd - 1)))) {
            labelEnd--;
          }
          endPos = labelEnd;
        }
        String name = tail.substring(0, endPos).trim();
        // 去掉尾部可能粘连的"起止日期/日期"标签或冒号
        name = name.replaceAll("起止日期$", "").replaceAll("日期$", "").trim();
        name = name.replaceAll("[：:]+$", "").trim();
        if (!name.isEmpty() && header.getAccountHolder() == null) {
          header.setAccountHolder(name);
        }
      }
    }

    // 起止日期：两个 YYYY-MM-DD
    Matcher dm = ANY_DATE.matcher(line);
    LocalDate first = null, second = null;
    while (dm.find()) {
      LocalDate d = LocalDate.parse(dm.group(1));
      if (first == null) first = d;
      else if (second == null) { second = d; break; }
    }
    if (first != null && header.getStartDate() == null) header.setStartDate(first);
    if (second != null && header.getEndDate() == null) header.setEndDate(second);
  }

  private static TransactionDTO buildTransaction(PdfColumnTableReader.Row row) {
    Matcher dm = ROW_DATE.matcher(text(row, "transTime"));
    if (!dm.find()) return null;
    LocalDate date;
    try {
      date = LocalDate.parse(dm.group());
    } catch (Exception e) {
      return null;
    }

    TransactionDTO tx = new TransactionDTO();
    tx.setSeqNo(intValue(text(row, "seqNo"), 0));
    tx.setTransDate(date);
    Matcher tm = Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})").matcher(text(row, "transTime"));
    if (tm.find()) {
      String time = tm.group(1);
      tx.setTransTime(time.length() == 7 ? "0" + time : time);
    }

    // 金额可能带一个负号（支出）或两个负号（"--100,000.00"，表示冲正）
    String amountText = text(row, "amount");
    String sign = amountText.replaceAll("[^+\\-]", "");
    String digits = amountText.replaceAll("[^\\d.,]", "");
    BigDecimal value = amount(digits);
    if (value == null) value = BigDecimal.ZERO;
    value = value.abs();
    boolean reversal = sign.length() >= 2;
    BigDecimal amount;
    String direction;
    if (reversal) {
      // 冲正：资金回补，余额增加，但业务方向仍记支出
      amount = value;
      direction = "出";
    } else if (sign.contains("-")) {
      amount = value.negate();
      direction = "出";
    } else {
      amount = value;
      direction = "入";
    }
    tx.setAmount(amount);
    tx.setBalance(amount(row.get("balance")));
    tx.setDirection(direction);
    tx.setAmountSignExplicit(true);

    tx.setCounterpartyAccount(account(row.get("counterpartyAccount")));
    tx.setCounterpartyName(compact(row.get("counterpartyName")));
    tx.setChannel(compact(row.get("channel")));
    tx.setPurpose(compact(row.get("purpose")));
    tx.setSummary(compact(row.get("summary")));
    tx.setPostscript(compact(row.get("postscript")));
    tx.setRemark(compact(row.get("remark")));
    return tx;
  }

  private static String text(PdfColumnTableReader.Row row, String field) {
    String value = row.get(field);
    return value == null ? "" : value;
  }

  private static int intValue(String text, int fallback) {
    try {
      return Integer.parseInt(text.replaceAll("[^0-9]", ""));
    } catch (Exception e) {
      return fallback;
    }
  }

  private static BigDecimal amount(String text) {
    String s = text == null ? "" : text.replaceAll("[^0-9.,\\-+]", "");
    if (s.isEmpty() || s.equals("-") || s.equals("+") || s.equals(".")) return null;
    try {
      return AmountNormalizer.normalize(s);
    } catch (Exception e) {
      return null;
    }
  }

  private static String account(String text) {
    if (text == null) return null;
    String value = text.replaceAll("\\s+", "");
    return value.matches("[0-9A-Za-z*]{6,}") ? value : null;
  }

  private static String compact(String text) {
    if (text == null) return null;
    String value = text.replaceAll("\\s+", "");
    return value.isEmpty() ? null : value;
  }

  private static void collectSummary(String text, StatementSummary s) {
    Matcher m;
    m = INCOME_SUM.matcher(text);
    if (m.find()) s.setTotalIncome(AmountNormalizer.normalize(m.group(1)));
    m = INCOME_COUNT.matcher(text);
    if (m.find()) s.setIncomeCount(Integer.parseInt(m.group(1)));
    m = EXPENSE_SUM.matcher(text);
    if (m.find()) s.setTotalExpense(AmountNormalizer.normalize(m.group(1)));
    m = EXPENSE_COUNT.matcher(text);
    if (m.find()) s.setExpenseCount(Integer.parseInt(m.group(1)));
    m = APPLY_TIME.matcher(text);
    if (m.find()) s.setApplyTime(m.group(1));
  }
}
