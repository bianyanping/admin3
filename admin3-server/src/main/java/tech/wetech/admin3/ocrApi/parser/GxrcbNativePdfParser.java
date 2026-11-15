package tech.wetech.admin3.ocrApi.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.AmountNormalizer;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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

  private static final Pattern TX_LINE = Pattern.compile(
    "^\\s*(\\d+)\\s+(\\d{4}-\\d{2}-\\d{2})\\s+([+\\-]{0,2}[\\d,.]+)\\s+([\\d,.]+)\\s+(.*)$");
  private static final Pattern INCOME_SUM = Pattern.compile("收入合计[：:]*\\s*([\\d,.]+)");
  private static final Pattern INCOME_COUNT = Pattern.compile("收入笔数[：:]*\\s*(\\d+)");
  private static final Pattern EXPENSE_SUM = Pattern.compile("支出合计[：:]*\\s*([\\d,.]+)");
  private static final Pattern EXPENSE_COUNT = Pattern.compile("支出笔数[：:]*\\s*(\\d+)");
  private static final Pattern APPLY_TIME = Pattern.compile("明细申请时间[：:]*\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})");

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
      for (int p = 1; p <= totalPages; p++) {
        stripper.setStartPage(p);
        stripper.setEndPage(p);
        String pageText = stripper.getText(doc);
        raw.append(pageText).append('\n');

        boolean firstPage = (p == 1);
        for (String line : pageText.split("\\r?\\n")) {
          String trimmed = line.trim();
          if (trimmed.isEmpty()) continue;

          if (firstPage) fillHeaderOnce(trimmed, header);

          Matcher txm = TX_LINE.matcher(trimmed);
          if (txm.find()) {
            TransactionDTO tx = buildTransaction(txm);
            transactions.add(tx);
          }
        }
      }

      // 末页汇总
      stripper.setStartPage(totalPages);
      stripper.setEndPage(totalPages);
      String lastPage = stripper.getText(doc);
      collectSummary(lastPage, summary);
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

  private static TransactionDTO buildTransaction(Matcher txm) {
    TransactionDTO tx = new TransactionDTO();
    tx.setSeqNo(Integer.parseInt(txm.group(1)));
    tx.setTransDate(LocalDate.parse(txm.group(2)));
    // 金额可能带一个负号（支出）或两个负号（"--100,000.00"，表示冲正）
    String sign = txm.group(3).replaceAll("[^+\\-]", "");
    String digits = txm.group(3).replaceAll("[^\\d.,]", "");
    BigDecimal value = AmountNormalizer.normalize(digits).abs();
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
    tx.setBalance(AmountNormalizer.normalize(txm.group(4)));
    tx.setDirection(direction);
    tx.setAmountSignExplicit(true);

    // pdfbox 把对方账号/户名/渠道/用途/摘要/附言/备注粘成一个 rest 段
    String rest = txm.group(5);
    String[] tokens = rest.split("\\s+");
    int idx = 0;

    // 找对方账号：6+ 位纯数字，且不是交易时间
    for (; idx < tokens.length; idx++) {
      String tk = tokens[idx];
      if (tk.matches("\\d{6,}") && !tk.matches("\\d{2}:\\d{2}:\\d{2}")) {
        tx.setCounterpartyAccount(tk);
        idx++;
        break;
      }
    }

    // 对方户名：到下一个数字字段为止（含中文/英文/有限公司）
    StringBuilder name = new StringBuilder();
    for (; idx < tokens.length; idx++) {
      String tk = tokens[idx];
      if (tk.matches("\\d{6,}") || tk.matches("\\d{2}:\\d{2}:\\d{2}")) break;
      name.append(tk);
    }
    String nameStr = name.toString().trim();
    tx.setCounterpartyName(nameStr.isEmpty() ? null : nameStr);

    // 剩下所有 token 塞摘要
    StringBuilder remaining = new StringBuilder();
    for (; idx < tokens.length; idx++) {
      if (remaining.length() > 0) remaining.append(' ');
      remaining.append(tokens[idx]);
    }
    String restStr = remaining.toString().trim();
    tx.setSummary(restStr.isEmpty() ? null : restStr);

    return tx;
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
