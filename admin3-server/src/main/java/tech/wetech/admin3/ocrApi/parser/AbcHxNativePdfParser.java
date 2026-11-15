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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国农业银行（港兴支行）原生 PDF 解析器。
 *
 * <p>版式（pdfbox 实际抽到的样子）：
 * <pre>
 *   第1行：账号:20-xxxx 户名:xxx公司 1日 - 2026年10月   ← pdfbox 把起止日期折成了两行
 *   第2行：048101040006413公司 币种:人民币 起止日期: 2026年04月0
 *   交易行（两种格式）：
 *     格式A：HH:MM:SS AMOUNT BALANCE rest...              ← 常见
 *     格式B：D HH:MM:SS AMOUNT BALANCE rest...             ← pdfbox 把日期里的"日"拆到了行首
 *   日期行（两种格式）：
 *     格式1：2026-10-27                                    ← 独立日期
 *     格式2：2026-10-27 1134521739                         ← 日期 + 流水号（pdfbox 粘一起）
 *   末页汇总：143笔 476504.84 / 123笔 477965.46
 * </pre>
 *
 * <p>无序号列。方向靠余额差反推。起止日期不靠页眉（pdfbox 折行），
 * 从解析到的第一笔和最后一笔交易日期反推。
 */
public final class AbcHxNativePdfParser {

  /** 账号 */
  private static final Pattern ACCOUNT_FULL = Pattern.compile("账号[：:]?\\s*(\\d{2}-\\d{10,}|\\d{13,})");
  private static final Pattern ACCOUNT_NUM = Pattern.compile("\\b(\\d{13,})\\b");
  /** 户名：标签+值（pdfbox 可能把户名和后面的"有限公司"粘一起，或者把完整户名折到下一行） */
  private static final Pattern HOLDER = Pattern.compile("户名[：:]?\\s*(.+?)(?=币种|账号|止|$)");
  /** 户名值的兜底：账号之后到"币种"之前可能粘了残缺户名+有限公司 */
  private static final Pattern HOLDER_AFTER_ACCT = Pattern.compile(
    "(?:账号[：:]?\\s*)?(?:\\d{2}-)?\\d{13,}\\s*([\\u4e00-\\u9fa5A-Za-z]{2,}?)?\\s*(?=币种)");

  /** 日期行 A：独立 YYYY-MM-DD */
  private static final Pattern DATE1 = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})\\s*$");
  /** 日期行 B：YYYY-MM-DD + 流水号（pdfbox 粘一起） —— 用 startsWith */
  private static final Pattern DATE2 = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})(\\s+\\S+)?$");

  /** 交易行 A：时间开头 */
  private static final Pattern TX_A = Pattern.compile(
    "^(\\d{1,2}:\\d{2}:\\d{2})\\s+(-?[\\d,.]+)\\s+(-?[\\d,.]+)\\s+(.*)$");
  /** 交易行 B：日开头（1-2 位数字），然后时间 */
  private static final Pattern TX_B = Pattern.compile(
    "^(\\d{1,2})\\s+(\\d{1,2}:\\d{2}:\\d{2})\\s+(-?[\\d,.]+)\\s+(-?[\\d,.]+)\\s+(.*)$");

  /** 汇总 */
  private static final Pattern COUNT = Pattern.compile("(\\d+)笔");
  private static final Pattern BIG_AMOUNT = Pattern.compile("-?\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})?|-?\\d+(?:\\.\\d{2})?");

  private AbcHxNativePdfParser() {}

  public static BankStatementDTO parse(File pdfFile) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    header.setBankName("中国农业银行");
    header.setCurrency("人民币");

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      PDFTextStripper s = new PDFTextStripper();
      s.setSortByPosition(true);
      String firstPage = null;
      String lastPage = null;

      LocalDate pendingDate = null;
      int lastSeenDay = -1;  // 用于 TX_B：补推断月份

      for (int p = 1; p <= doc.getNumberOfPages(); p++) {
        s.setStartPage(p); s.setEndPage(p);
        String pageText = s.getText(doc);
        raw.append(pageText).append('\n');
        if (p == 1) firstPage = pageText;
        if (p == doc.getNumberOfPages()) lastPage = pageText;

        for (String line : pageText.split("\\r?\\n")) {
          String t = line.trim();
          if (t.isEmpty()) continue;

          if (p == 1) fillHeaderOnce(t, header);

          // 日期行 A（独立）
          Matcher d1m = DATE1.matcher(t);
          if (d1m.matches()) {
            pendingDate = LocalDate.parse(d1m.group(1));
            lastSeenDay = pendingDate.getDayOfMonth();
            continue;
          }
          // 日期行 B（YYYY-MM-DD + 流水号）
          Matcher d2m = DATE2.matcher(t);
          if (d2m.matches()) {
            pendingDate = LocalDate.parse(d2m.group(1));
            lastSeenDay = pendingDate.getDayOfMonth();
            continue;
          }

          // 交易行 A：时间开头（最常见）
          Matcher ta = TX_A.matcher(t);
          if (ta.find()) {
            transactions.add(buildFromA(ta, pendingDate));
            pendingDate = null;
            lastSeenDay = -1;
            continue;
          }

          // 交易行 B：日开头 + 时间（pdfbox 把日期里的"日"拆到了行首）
          Matcher tb = TX_B.matcher(t);
          if (tb.find()) {
            int day = Integer.parseInt(tb.group(1));
            LocalDate d = pendingDate;
            // 如果 pendingDate 为 null 或 day 与 pendingDate 对不上，尝试用 lastSeenDay 推断
            if (d == null || d.getDayOfMonth() != day) {
              // 找不到就先用一个占位，后面靠继承修复
              if (d != null) {
                // 月份/年份继承，只替换 day
                try { d = d.withDayOfMonth(day); } catch (Exception e) { /* 保持原样 */ }
              }
            }
            transactions.add(buildFromB(tb, d));
            pendingDate = null;
            lastSeenDay = day;
            continue;
          }
        }
      }

      if (firstPage != null) fillHeaderFallback(firstPage, header);
      if (lastPage != null) fillSummary(lastPage, summary);
    }

    // 兜底：日期继承 + 从首末笔反推起止日期
    LocalDate lastKnownDate = null;
    LocalDate earliest = null, latest = null;
    for (TransactionDTO tx : transactions) {
      LocalDate d = tx.getTransDate();
      if (d == null || d.getYear() <= 1970) {
        if (lastKnownDate != null) {
          tx.setTransDate(lastKnownDate);
          d = lastKnownDate;
          if (tx.getTransTime() != null) {
            try { tx.setTransDateTime(LocalDateTime.of(d, LocalTime.parse(tx.getTransTime()))); }
            catch (Exception ignored) {}
          }
        }
      } else {
        lastKnownDate = d;
      }
      if (d != null && d.getYear() > 1970) {
        if (earliest == null || d.isBefore(earliest)) earliest = d;
        if (latest == null || d.isAfter(latest)) latest = d;
      }
    }

    // 起止日期从交易反推
    if (header.getStartDate() == null && earliest != null) header.setStartDate(earliest);
    if (header.getEndDate() == null && latest != null) header.setEndDate(latest);
    // 即使有，也验证（农行页眉经常折行导致 start/end 混乱）
    if (header.getStartDate() != null && header.getEndDate() != null
        && header.getStartDate().isAfter(header.getEndDate())) {
      LocalDate t = header.getStartDate();
      header.setStartDate(header.getEndDate());
      header.setEndDate(t);
    }
    // 交易数据比页眉更可靠
    if (earliest != null && latest != null) {
      if (header.getStartDate() == null || earliest.isBefore(header.getStartDate())) header.setStartDate(earliest);
      if (header.getEndDate() == null || latest.isAfter(header.getEndDate())) header.setEndDate(latest);
    }

    // 按余额差重算每笔金额（自动适配正序 / 倒序流水）
    finalizeAmounts(transactions);

    // 汇总兜底
    recalcIfNeeded(transactions, summary);

    // seqNo
    for (int i = 0; i < transactions.size(); i++) transactions.get(i).setSeqNo(i + 1);

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  /**
   * 按余额差重算每笔金额。
   *
   * <p>农行 PDF 里的「发生额」列不带方向，原实现用「本行余额 − 上一行余额」反推，
   * 只适用于正序（最早在前）流水。若是倒序（最新在前），差额属于<b>上一行</b>，
   * 直接套用会把每笔金额整体错位一行。这里先判断顺序再取相邻行：
   * <ul>
   *   <li>正序：amount(i) = balance(i) − balance(i−1)</li>
   *   <li>倒序：amount(i) = balance(i) − balance(i+1)</li>
   * </ul>
   */
  private static void finalizeAmounts(List<TransactionDTO> txs) {
    if (txs.isEmpty()) return;
    boolean descending = isDescending(txs);

    for (int i = 0; i < txs.size(); i++) {
      TransactionDTO tx = txs.get(i);
      BigDecimal raw = tx.getAmount();
      BigDecimal balance = tx.getBalance();
      BigDecimal amount = null;

      if (descending) {
        if (i + 1 < txs.size()) {
          BigDecimal nextBalance = txs.get(i + 1).getBalance();
          if (balance != null && nextBalance != null) {
            amount = balance.subtract(nextBalance);
          }
        }
      } else {
        if (i > 0) {
          BigDecimal prevBalance = txs.get(i - 1).getBalance();
          if (balance != null && prevBalance != null) {
            amount = balance.subtract(prevBalance);
          }
        }
      }

      // 首行（正序）或末行（倒序）无法用余额差推出，退回 PDF 原始发生额
      if (amount == null) {
        amount = raw == null ? BigDecimal.ZERO : raw;
      }
      tx.setAmount(amount);
      tx.setDirection(amount.signum() >= 0 ? "入" : "出");
      tx.setAmountSignExplicit(false);
    }
  }

  /**
   * 判断明细是否为倒序（最新在前）：比较首末有效交易日期，日期相同则退回比较余额无关的兜底为 false。
   */
  private static boolean isDescending(List<TransactionDTO> txs) {
    LocalDate first = null;
    LocalDate last = null;
    for (TransactionDTO tx : txs) {
      LocalDate d = tx.getTransDate();
      if (d == null || d.getYear() <= 1970) continue;
      if (first == null) first = d;
      last = d;
    }
    return first != null && last != null && first.isAfter(last);
  }

  /** 交易行 A 的构建 */
  private static TransactionDTO buildFromA(Matcher m, LocalDate d) {
    TransactionDTO tx = new TransactionDTO();
    tx.setTransDate(d == null ? LocalDate.of(1970, 1, 1) : d);
    String timeStr = m.group(1);
    if (timeStr.length() == 7) timeStr = "0" + timeStr;
    try {
      tx.setTransTime(timeStr);
      if (d != null) tx.setTransDateTime(LocalDateTime.of(d, LocalTime.parse(timeStr)));
    } catch (Exception ignored) {}
    BigDecimal rawAmt = AmountNormalizer.normalize(m.group(2));
    tx.setAmount(rawAmt == null ? BigDecimal.ZERO : rawAmt);
    tx.setBalance(AmountNormalizer.normalize(m.group(3)));
    fillRest(tx, m.group(4));
    return tx;
  }

  /** 交易行 B 的构建 */
  private static TransactionDTO buildFromB(Matcher m, LocalDate d) {
    TransactionDTO tx = new TransactionDTO();
    tx.setTransDate(d == null ? LocalDate.of(1970, 1, 1) : d);
    String timeStr = m.group(2);
    if (timeStr.length() == 7) timeStr = "0" + timeStr;
    try {
      tx.setTransTime(timeStr);
      if (d != null) tx.setTransDateTime(LocalDateTime.of(d, LocalTime.parse(timeStr)));
    } catch (Exception ignored) {}
    BigDecimal rawAmt = AmountNormalizer.normalize(m.group(3));
    tx.setAmount(rawAmt == null ? BigDecimal.ZERO : rawAmt);
    tx.setBalance(AmountNormalizer.normalize(m.group(4)));
    fillRest(tx, m.group(5));
    return tx;
  }

  private static void recalcIfNeeded(List<TransactionDTO> txs, StatementSummary s) {
    int declaredTotal = (s.getIncomeCount() == null ? 0 : s.getIncomeCount())
                      + (s.getExpenseCount() == null ? 0 : s.getExpenseCount());
    if (declaredTotal == 0 || declaredTotal < txs.size()) {
      BigDecimal inc = BigDecimal.ZERO, exp = BigDecimal.ZERO;
      int ic = 0, ec = 0;
      for (TransactionDTO tx : txs) {
        BigDecimal a = tx.getAmount();
        if (a == null || a.signum() == 0) continue;
        if (a.signum() >= 0) { inc = inc.add(a); ic++; }
        else { exp = exp.add(a.negate()); ec++; }
      }
      s.setTotalIncome(inc); s.setIncomeCount(ic);
      s.setTotalExpense(exp); s.setExpenseCount(ec);
    }
  }

  private static void fillHeaderOnce(String line, StatementHeader h) {
    Matcher m;
    if (h.getAccountNo() == null) {
      m = ACCOUNT_FULL.matcher(line);
      if (m.find()) h.setAccountNo(m.group(1).replaceFirst("^\\d{2}-", ""));
    }
    if (h.getAccountHolder() == null) {
      m = HOLDER.matcher(line);
      if (m.find()) {
        String v = sanitizeHolder(m.group(1));
        if (!v.isEmpty()) h.setAccountHolder(v);
      }
    }
  }

  private static void fillHeaderFallback(String firstPage, StatementHeader h) {
    if (h.getAccountNo() == null) {
      Matcher m = ACCOUNT_NUM.matcher(firstPage);
      if (m.find()) h.setAccountNo(m.group(1));
    }
    // 户名兜底：在**全文**再跑一次 HOLDER（fillHeaderOnce 是逐行，可能命中残缺值）
    // 如果之前已经 set 过，对比哪个更长就用哪个——全文匹配能拿到跨行完整值
    {
      Matcher m = HOLDER.matcher(firstPage);
      if (m.find()) {
        String full = sanitizeHolder(m.group(1));
        if (!full.isEmpty()) {
          String old = h.getAccountHolder();
          if (old == null || full.length() > old.length()) h.setAccountHolder(full);
        }
      }
    }
    // 最后兜底：账号之后、币种之前的 CJK 串（可能是折行丢的户名尾部，如"有限公司"）
    {
      Matcher m = HOLDER_AFTER_ACCT.matcher(firstPage);
      if (m.find() && m.group(1) != null) {
        String tail = m.group(1).trim();
        if (!tail.isEmpty()) {
          String cur = h.getAccountHolder();
          if (cur == null) {
            h.setAccountHolder(tail);
          } else if (tail.length() > cur.length() || cur.endsWith("管理") || cur.endsWith("商") || cur.endsWith("业")) {
            // 当前以"管理/商/业"结尾——典型的被折行截断信号，把 tail 拼上
            if (!cur.contains(tail)) h.setAccountHolder(cur + tail);
          } else if (cur.length() < tail.length()) {
            h.setAccountHolder(tail);
          }
        }
      }
    }
  }

  /** pdfbox 折行会把 " 1日 - 2026年10月" 之类粘到户名值尾部，截掉这些噪音 */
  private static String sanitizeHolder(String raw) {
    String v = raw.trim();
    // 截掉尾部形如 " 1日 - 2026年10月" 的噪音（允许后面跟任意字符直到末尾）
    v = v.replaceAll("\\s+\\d{1,2}日.*$", "");
    return v.trim();
  }

  /** 末页汇总 */
  private static void fillSummary(String lastPage, StatementSummary s) {
    List<Integer> counts = new ArrayList<>();
    Matcher cm = COUNT.matcher(lastPage);
    while (cm.find()) counts.add(Integer.parseInt(cm.group(1)));

    List<BigDecimal> amounts = new ArrayList<>();
    for (String line : lastPage.split("\\r?\\n")) {
      Matcher lm = BIG_AMOUNT.matcher(line);
      while (lm.find()) {
        BigDecimal bd = AmountNormalizer.normalize(lm.group());
        if (bd != null && bd.abs().compareTo(BigDecimal.valueOf(1_000)) > 0) amounts.add(bd.abs());
      }
    }
    if (counts.size() >= 2 && amounts.size() >= 2) {
      int c1 = counts.get(0), c2 = counts.get(1);
      BigDecimal a1 = amounts.get(0), a2 = amounts.get(1);
      // 金额 小→大 排序；方向不确定，靠 recalcIfNeeded 兜底时会反算
      if (a1.compareTo(a2) > 0) { BigDecimal t = a1; a1 = a2; a2 = t; }
      s.setTotalIncome(a1); s.setTotalExpense(a2);
      if (c1 > c2) { int t = c1; c1 = c2; c2 = t; }
      s.setIncomeCount(c1); s.setExpenseCount(c2);
    }
  }

  private static void fillRest(TransactionDTO tx, String rest) {
    String[] tokens = rest.split("\\s+");
    StringBuilder before = new StringBuilder();
    String counterpartyAccount = null;
    StringBuilder after = new StringBuilder();
    boolean found = false;
    for (String tk : tokens) {
      if (!found && tk.matches("\\d{6,}") && !tk.matches("\\d{1,2}:\\d{2}:\\d{2}")) {
        counterpartyAccount = tk;
        found = true;
        continue;
      }
      if (!found) { if (before.length() > 0) before.append(' '); before.append(tk); }
      else { if (after.length() > 0) after.append(' '); after.append(tk); }
    }
    tx.setCounterpartyAccount(counterpartyAccount);
    String name = after.toString().trim();
    tx.setCounterpartyName(name.isEmpty() ? null : name);
    String summary = before.toString().trim();
    tx.setSummary(summary.isEmpty() ? null : summary);
  }
}
