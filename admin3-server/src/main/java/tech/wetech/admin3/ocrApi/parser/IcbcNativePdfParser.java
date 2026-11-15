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
 * 中国工商银行个人账户交易明细（原生 PDF）解析器。
 *
 * <p>工行各分行的明细表列顺序略有差异，但交易行有共同特征：
 * 以交易日期开头，后面跟交易时间（可选）、摘要、交易金额、账户余额、对方账号、对方户名。
 *
 * <p>解析策略：
 * <ol>
 *   <li>用「日期开头 + 至少两个金额」识别交易行，其余行（表头、分页、合计、声明）跳过；</li>
 *   <li>先只取金额绝对值与余额，收齐全部行之后再定方向：
 *       明细可能是正序（旧→新）也可能跨页倒序（新→旧），
 *       但每行余额一定连续，所以「本行余额 − 相邻行余额」就是本行带符号金额；
 *       仅当这个差值与金额绝对值一致时才采信，避免漏行时掩盖问题；</li>
 *   <li>余额差对不上时，回退到金额自带的正负号、「借/贷」文字列判断方向。</li>
 * </ol>
 */
public final class IcbcNativePdfParser {

  private static final Pattern ACCOUNT = Pattern.compile("(?:卡号|账号|账户)[：:\\s]*([0-9*Xx]{6,})");
  private static final Pattern HOLDER = Pattern.compile("(?:户名|姓名|账户名称)[：:\\s]*([^\\s，,]+)");
  private static final Pattern DATE_RANGE = Pattern.compile(
    "(?:起止日期|统计期间|查询期间|交易期间|时间范围|查询时间)[：:\\s]*"
      + "(\\d{4}[-/年]?\\d{1,2}[-/月]?\\d{1,2})\\s*(?:至|到|-|~|—|－)\\s*"
      + "(\\d{4}[-/年]?\\d{1,2}[-/月]?\\d{1,2})");
  private static final Pattern CURRENCY = Pattern.compile("币种[：:\\s]*([^\\s]+)");

  /** 交易行：日期开头 + 其余内容 */
  private static final Pattern ROW = Pattern.compile("^(\\d{4}[-/]?\\d{1,2}[-/]?\\d{1,2})\\s+(.+)$");
  /** 交易时间 */
  private static final Pattern TIME = Pattern.compile("^(\\d{2}:\\d{2}(?::\\d{2})?)\\s+(.+)$");
  /** 金额 / 余额 */
  private static final Pattern MONEY = Pattern.compile("^([-+])?[¥￥]?([\\d,]*\\d\\.\\d{2})元?$");
  /** 对方账号（可能带掩码） */
  private static final Pattern COUNTERPARTY_ACCOUNT = Pattern.compile("^[0-9*Xx]{6,}$");
  /** 分页行：---- 第1页/共3页 ---- */
  private static final Pattern PAGE_SEP = Pattern.compile("^[-—\\s]*第\\d+页[/／]共\\d+页[-—\\s]*$");

  /** 金额比较容差 */
  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  private static final String[] SKIP_PREFIXES = {
    "交易日期", "序号", "卡号", "账号", "户名", "合计", "小计", "总计",
    "当前时间段", "温馨提示", "打印时间", "生成时间", "第", "中国工商银行", "个人账户"
  };

  private IcbcNativePdfParser() {
  }

  public static BankStatementDTO parse(File pdfFile) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<RawRow> rows = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    header.setBankName("中国工商银行");

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);

      for (int p = 1; p <= doc.getNumberOfPages(); p++) {
        stripper.setStartPage(p);
        stripper.setEndPage(p);
        String pageText = stripper.getText(doc);
        raw.append(pageText).append('\n');

        if (p == 1) {
          fillHeader(pageText, header);
        }

        for (String line : pageText.split("\\r?\\n")) {
          RawRow row = parseRow(line.trim());
          if (row != null) {
            rows.add(row);
          }
        }
      }
    }

    List<TransactionDTO> transactions = buildTransactions(rows);

    // 账期兜底：表头缺失时取明细的最早/最晚日期（倒序流水首末笔日期是反的，不能直接用）
    LocalDate minDate = null;
    LocalDate maxDate = null;
    for (TransactionDTO tx : transactions) {
      LocalDate d = tx.getTransDate();
      if (d == null) {
        continue;
      }
      if (minDate == null || d.isBefore(minDate)) {
        minDate = d;
      }
      if (maxDate == null || d.isAfter(maxDate)) {
        maxDate = d;
      }
    }
    if (header.getStartDate() == null) {
      header.setStartDate(minDate);
    }
    if (header.getEndDate() == null) {
      header.setEndDate(maxDate);
    }

    recalcTotals(transactions, summary);

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  /** 解析出的一行原始数据：先不定方向，等全部行收齐后用余额差统一判定 */
  private record RawRow(LocalDate date, String time, BigDecimal magnitude, BigDecimal balance,
                        boolean negativeSign, boolean positiveSign, String directionFlag,
                        String counterpartyAccount, List<String> texts) {
  }

  private record Amt(BigDecimal value, boolean negative, boolean positive) {
  }

  private static RawRow parseRow(String t) {
    if (t.isEmpty() || PAGE_SEP.matcher(t).find() || isSkippable(t)) {
      return null;
    }
    Matcher rm = ROW.matcher(t);
    if (!rm.find()) {
      return null;
    }
    LocalDate date = parseDate(rm.group(1));
    String rest = rm.group(2).trim();

    String transTime = null;
    Matcher tm = TIME.matcher(rest);
    if (tm.find()) {
      transTime = tm.group(1);
      rest = tm.group(2).trim();
    }

    // ===== 拆分金额与文字 =====
    List<Amt> amounts = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    String counterpartyAccount = null;
    String directionFlag = null;

    for (String token : rest.split("\\s+")) {
      Matcher mm = MONEY.matcher(token);
      if (mm.matches()) {
        amounts.add(new Amt(AmountNormalizer.normalize(mm.group(2)),
          "-".equals(mm.group(1)), "+".equals(mm.group(1))));
        continue;
      }
      if (COUNTERPARTY_ACCOUNT.matcher(token).matches()) {
        if (counterpartyAccount == null) {
          counterpartyAccount = token;
        }
        continue;
      }
      String flag = directionFlagOf(token);
      if (flag != null) {
        directionFlag = flag;
        continue;
      }
      texts.add(token);
    }

    // 交易行至少要「金额 + 余额」两个数字
    if (amounts.size() < 2) {
      return null;
    }

    BigDecimal balance = amounts.get(amounts.size() - 1).value();
    Amt amountToken = null;
    for (int i = 0; i < amounts.size() - 1; i++) {
      if (amounts.get(i).value().signum() != 0) {
        amountToken = amounts.get(i);
        break;
      }
    }
    if (amountToken == null) {
      amountToken = amounts.get(0);
    }

    return new RawRow(date, transTime, amountToken.value().abs(), balance,
      amountToken.negative(), amountToken.positive(), directionFlag,
      counterpartyAccount, texts);
  }

  /**
   * 收齐所有行后统一生成交易列表。
   *
   * <p>方向判定优先级：
   * <ol>
   *   <li>余额差：明细倒序（新→旧）时本行金额 = 本行余额 − 下一行余额，正序时 = 本行余额 − 上一行余额；
   *       与金额绝对值一致才采信（红字冲正行金额显示负数但实际是入账，靠这一步纠正）；</li>
   *   <li>金额自带的正负号；</li>
   *   <li>「借/贷」列。</li>
   * </ol>
   */
  private static List<TransactionDTO> buildTransactions(List<RawRow> rows) {
    List<TransactionDTO> list = new ArrayList<>(rows.size());
    if (rows.isEmpty()) {
      return list;
    }
    boolean descending = isDescending(rows);

    int seqNo = 0;
    for (int i = 0; i < rows.size(); i++) {
      RawRow r = rows.get(i);
      BigDecimal amount = null;

      BigDecimal neighborBalance = null;
      if (descending && i + 1 < rows.size()) {
        neighborBalance = rows.get(i + 1).balance();
      } else if (!descending && i > 0) {
        neighborBalance = rows.get(i - 1).balance();
      }
      if (neighborBalance != null) {
        BigDecimal derived = r.balance().subtract(neighborBalance);
        if (derived.abs().subtract(r.magnitude()).abs().compareTo(TOLERANCE) <= 0) {
          amount = derived;
        }
      }

      if (amount == null) {
        amount = r.magnitude();
        if (r.negativeSign()) {
          amount = amount.negate();
        } else if (!r.positiveSign() && "出".equals(r.directionFlag())) {
          amount = amount.negate();
        }
      }

      TransactionDTO tx = new TransactionDTO();
      tx.setSeqNo(++seqNo);
      tx.setTransDate(r.date());
      tx.setTransTime(r.time());
      tx.setAmount(amount);
      tx.setBalance(r.balance());
      tx.setDirection(amount.signum() >= 0 ? "入" : "出");
      tx.setAmountSignExplicit(r.negativeSign() || r.positiveSign());
      tx.setCounterpartyAccount(r.counterpartyAccount());
      if (!r.texts().isEmpty()) {
        tx.setSummary(truncate(r.texts().get(0), 200));
      }
      if (r.texts().size() >= 2) {
        tx.setCounterpartyName(truncate(r.texts().get(r.texts().size() - 1), 100));
      }
      list.add(tx);
    }
    return list;
  }

  /** 明细是否为倒序（最新在前）：比首末交易日期，日期相同则比交易时间 */
  private static boolean isDescending(List<RawRow> rows) {
    RawRow first = rows.get(0);
    RawRow last = rows.get(rows.size() - 1);
    if (first.date() != null && last.date() != null && !first.date().equals(last.date())) {
      return first.date().isAfter(last.date());
    }
    String ft = first.time() == null ? "" : first.time();
    String lt = last.time() == null ? "" : last.time();
    return ft.compareTo(lt) > 0;
  }

  private static boolean isSkippable(String line) {
    for (String prefix : SKIP_PREFIXES) {
      if (line.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static String directionFlagOf(String token) {
    return switch (token) {
      case "收入", "入", "贷", "贷方", "进账", "转入" -> "入";
      case "支出", "出", "借", "借方", "出账", "转出" -> "出";
      default -> null;
    };
  }

  private static void fillHeader(String firstPage, StatementHeader h) {
    Matcher m;
    if (h.getAccountNo() == null) {
      m = ACCOUNT.matcher(firstPage);
      if (m.find()) {
        h.setAccountNo(m.group(1));
      }
    }
    if (h.getAccountHolder() == null) {
      m = HOLDER.matcher(firstPage);
      if (m.find()) {
        h.setAccountHolder(m.group(1).trim());
      }
    }
    if (h.getStartDate() == null) {
      m = DATE_RANGE.matcher(firstPage);
      if (m.find()) {
        h.setStartDate(parseDate(m.group(1)));
        h.setEndDate(parseDate(m.group(2)));
      }
    }
    if (h.getCurrency() == null) {
      m = CURRENCY.matcher(firstPage);
      if (m.find()) {
        h.setCurrency(m.group(1).replaceAll("元$", ""));
      }
    }
  }

  /**
   * 兼容 2025-01-01 / 2025/1/1 / 20250101 / 2025年1月1日 四种写法。
   */
  private static LocalDate parseDate(String s) {
    if (s == null) {
      return null;
    }
    String digits = s.replaceAll("[^0-9]", "");
    try {
      if (digits.length() == 8) {
        return LocalDate.of(
          Integer.parseInt(digits.substring(0, 4)),
          Integer.parseInt(digits.substring(4, 6)),
          Integer.parseInt(digits.substring(6, 8)));
      }
    } catch (Exception ignored) {
      // 落到下面返回 null
    }
    return null;
  }

  private static String truncate(String s, int max) {
    if (s == null) {
      return null;
    }
    return s.length() > max ? s.substring(0, max) : s;
  }

  private static void recalcTotals(List<TransactionDTO> transactions, StatementSummary summary) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expense = BigDecimal.ZERO;
    int incomeCount = 0;
    int expenseCount = 0;
    for (TransactionDTO tx : transactions) {
      BigDecimal amount = tx.getAmount();
      if (amount == null || amount.signum() == 0) {
        continue;
      }
      if (amount.signum() > 0) {
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
  }
}
