package tech.wetech.admin3.ocrApi.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
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
 * 原生 PDF 银行流水解析器。
 *
 * <p>版式：桂林国民村镇银行「本行账户明细」（8 列：交易时间 / 收入 / 支出 /
 * 余额 / 对方账户 / 对方户名 / 摘要 / 流水号）。
 *
 * <p>核心技巧：
 * <ol>
 *   <li>PDFBox {@link PDFTextStripper} 按位置排序抽纯文本，页眉的账号/户名/起止日期
 *       用正则命中，准确率 100%；</li>
 *   <li>表格里的空列（收入或支出的空单元格）PDFBox 会直接跳过，所以每行只有一个
 *       非空金额。方向用<b>余额差</b>反推：amount = curBalance − prevBalance，
 *       正=收入、负=支出。</li>
 * </ol>
 */
public final class NativePdfStatementParser {

  private static final Pattern HEADER_DATE = Pattern.compile(
    "开始日期[：:]*\\s*(\\d{4}-\\d{2}-\\d{2})\\s*结束日期[：:]*\\s*(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern ACCOUNT = Pattern.compile("账号[：:]*\\s*(\\d{6,})");
  private static final Pattern HOLDER = Pattern.compile("户名[：:]*\\s*(\\S+)");
  private static final Pattern CURRENCY = Pattern.compile("币种[：:]*\\s*(\\S+)");
  private static final Pattern AMOUNT_STRICT = Pattern.compile("^\\d+\\.\\d{2}$");

  /** 对方账户/对方户名会折行，按坐标还原 */
  private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
  static {
    COLUMNS.put("交易时间", "transTime");
    COLUMNS.put("收入", "income");
    COLUMNS.put("支出", "expense");
    COLUMNS.put("余额", "balance");
    COLUMNS.put("对方账户", "counterpartyAccount");
    COLUMNS.put("对方户名", "counterpartyName");
    COLUMNS.put("摘要", "summary");
    COLUMNS.put("流水号", "serial");
  }
  private static final Set<String> REQUIRED_COLUMNS = Set.of(
    "transTime", "income", "expense", "balance", "counterpartyAccount", "counterpartyName");
  private static final Pattern ROW_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  private NativePdfStatementParser() {}

  public static BankStatementDTO parse(File pdfFile, String bankCode) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    header.setBankName(resolveBankName(bankCode));

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);

      for (int p = 1; p <= doc.getNumberOfPages(); p++) {
        stripper.setStartPage(p);
        stripper.setEndPage(p);
        String pageText = stripper.getText(doc);
        raw.append(pageText).append('\n');

        for (String line : pageText.split("\\r?\\n")) {
          String trimmed = line.trim();
          if (trimmed.isEmpty()) continue;

          // ===== 页眉（只命中一次）=====
          Matcher dm = HEADER_DATE.matcher(trimmed);
          if (dm.find() && header.getStartDate() == null) {
            header.setStartDate(toDate(dm.group(1)));
            header.setEndDate(toDate(dm.group(2)));
          }
          Matcher am = ACCOUNT.matcher(trimmed);
          if (am.find() && header.getAccountNo() == null) header.setAccountNo(am.group(1));
          Matcher hm = HOLDER.matcher(trimmed);
          if (hm.find() && header.getAccountHolder() == null) header.setAccountHolder(amendHolder(hm.group(1)));
          Matcher cm = CURRENCY.matcher(trimmed);
          if (cm.find() && header.getCurrency() == null) header.setCurrency(cm.group(1));
        }
      }

      // 对方账户/对方户名等列会折行，按坐标还原整行
      BigDecimal prevBalance = null;
      int seqNo = 0;
      for (PdfColumnTableReader.Row row : PdfColumnTableReader.read(
        doc, COLUMNS, REQUIRED_COLUMNS, "transTime", ROW_DATE)) {
        String dateText = row.get("transTime");
        if (dateText == null) continue;
        Matcher dm = ROW_DATE.matcher(dateText);
        if (!dm.find()) continue;
        LocalDate date = toDate(dm.group());
        if (date == null) continue;

        BigDecimal income = tryAmount(row.get("income"));
        BigDecimal expense = tryAmount(row.get("expense"));
        BigDecimal balance = tryAmount(row.get("balance"));
        if (balance == null) continue;

        BigDecimal amount = income != null ? income : (expense == null ? BigDecimal.ZERO : expense.negate());
        // 余额差校正，防止个别行金额列缺失
        if (prevBalance != null && prevBalance.add(amount).compareTo(balance) != 0) {
          amount = balance.subtract(prevBalance);
        }
        prevBalance = balance;

        TransactionDTO tx = new TransactionDTO();
        tx.setSeqNo(++seqNo);
        tx.setTransDate(date);
        tx.setAmount(amount);
        tx.setBalance(balance);
        tx.setDirection(amount.signum() >= 0 ? "入" : "出");
        tx.setAmountSignExplicit(false);
        tx.setCounterpartyAccount(compact(row.get("counterpartyAccount")));
        tx.setCounterpartyName(compact(row.get("counterpartyName")));
        tx.setSummary(compact(row.get("summary")));
        String serial = compact(row.get("serial"));
        if (serial != null) tx.setRemark("流水号:" + serial);
        transactions.add(tx);
      }
    }

    // 汇总
    BigDecimal income = BigDecimal.ZERO, expense = BigDecimal.ZERO;
    int incomeCount = 0, expenseCount = 0;
    for (TransactionDTO tx : transactions) {
      BigDecimal amount = tx.getAmount();
      if (amount == null || amount.signum() == 0) continue;
      if (amount.signum() >= 0) {
        income = income.add(amount);
        incomeCount++;
      } else {
        expense = expense.add(amount.negate());
        expenseCount++;
      }
    }
    summary.setTotalIncome(income);
    summary.setIncomeCount(incomeCount);
    summary.setTotalExpense(expense);
    summary.setExpenseCount(expenseCount);

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString()); // 复用字段名
    return dto;
  }

  // ---------- 工具 ----------

  private static String resolveBankName(String bankCode) {
    if (bankCode == null) return null;
    return switch (bankCode.toUpperCase()) {
      case "GLGMCZYH" -> "桂林国民村镇银行";
      default -> bankCode;
    };
  }

  /**
   * pdfbox 抽户名时，尾部往往跟着"币种：人民币"这类片段被空白字符粘连，
   * 这里把它截掉。
   */
  private static String amendHolder(String holder) {
    if (holder == null) return null;
    return holder.replaceFirst("币种.*$", "").trim();
  }

  private static String compact(String text) {
    if (text == null) return null;
    String value = text.replaceAll("\\s+", "");
    return value.isEmpty() ? null : value;
  }

  private static LocalDate toDate(String s) {
    try {
      return LocalDate.parse(s);
    } catch (Exception e) {
      return null;
    }
  }

  private static BigDecimal tryAmount(String s) {
    if (s == null || s.isEmpty()) return null;
    String cleaned = s.replaceAll("[^0-9.]", "");
    if (!AMOUNT_STRICT.matcher(cleaned).matches()) return null;
    try {
      BigDecimal bd = new BigDecimal(cleaned);
      return bd.signum() == 0 ? null : bd;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
