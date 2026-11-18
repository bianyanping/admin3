package tech.wetech.admin3.ocrApi.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
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
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国邮政储蓄银行原生 PDF 解析器（账户交易明细专用回单）。
 *
 * <p>版式（列顺序由表头确定）：
 * <pre>
 *   序号 交易时间 记账日期 支出金额 收入金额 余额 对方账号 对方户名 对方行名 用途 附言 摘要 交易流水号 全局路由号
 * </pre>
 *
 * <p>难点：PDFBox 按行输出文本时，大额金额的末位字符会被折到下一行、行内各列也可能错位到相邻行，
 * 简单的逐行正则会导致列错位、金额串行。因此这里改为<b>按坐标重建表格</b>：
 * 以「序号」为行锚点，在行窗口内按列的 X 区间收集字符并拼接，再把 支出/收入 转成带符号金额。
 *
 * <p>注意：该流水存在负数支出（冲正/退款），表头合计按净额计算，解析时必须保留符号。
 */
public final class PsbcNativePdfParser {

  private static final Pattern ACCOUNT = Pattern.compile("账号\\s*(\\d{10,})");
  private static final Pattern ACCOUNT_NUM = Pattern.compile("\\b(\\d{15,})\\b");
  private static final Pattern DATE_RANGE = Pattern.compile(
    "起止日期\\s*(\\d{4})年(\\d{2})月(\\d{2})日\\s*[-~至]\\s*(\\d{4})年(\\d{2})月(\\d{2})日");
  private static final Pattern ANY_DATE = Pattern.compile("(\\d{4}[-年]\\d{2}[-月]\\d{2})");
  private static final Pattern BIG_AMOUNT = Pattern.compile("-?\\d{1,3}(?:,\\d{3})*(?:\\.\\d{2})?|-?\\d+(?:\\.\\d{2})?");
  private static final Pattern COUNT_SUFFIX = Pattern.compile("(\\d+)笔");
  private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern TIME = Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})");

  /** 首末行没有相邻锚点时，按行高的一半留出上下界 */
  private static final float EDGE_ROW_MARGIN = 26f;

  private PsbcNativePdfParser() {}

  public static BankStatementDTO parse(File pdfFile) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    header.setBankName("中国邮政储蓄银行");
    header.setCurrency("人民币");

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      // 第一遍：纯文本，用于页眉信息和原始文本留存
      PDFTextStripper textStripper = new PDFTextStripper();
      textStripper.setSortByPosition(true);
      String firstPageText = null;
      for (int p = 1; p <= doc.getNumberOfPages(); p++) {
        textStripper.setStartPage(p);
        textStripper.setEndPage(p);
        String pageText = textStripper.getText(doc);
        raw.append(pageText).append('\n');
        if (p == 1) {
          firstPageText = pageText;
          for (String line : pageText.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) fillHeaderOnce(t, header, summary);
          }
        }
      }
      if (firstPageText != null) fillHeaderFallback(firstPageText, header, summary);

      // 第二遍：按坐标重建交易行
      for (int p = 1; p <= doc.getNumberOfPages(); p++) {
        List<Pos> positions = collectPositions(doc, p);
        buildTransactions(positions, transactions);
      }
    }

    // 表头合计按净额口径（含负数冲正），存在时以表头为准，仅缺失时用明细反算
    if (summary.getTotalIncome() == null || summary.getTotalExpense() == null) {
      recalcSummary(transactions, summary);
    }

    for (TransactionDTO tx : transactions) {
      if (tx.getTransDate() != null && tx.getTransTime() != null) {
        try {
          tx.setTransDateTime(LocalDateTime.of(tx.getTransDate(), LocalTime.parse(tx.getTransTime())));
        } catch (Exception ignored) {
        }
      }
    }

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  // ==================== 坐标采集与表格重建 ====================

  private static final class Pos {
    final float x;
    final float y;
    final float w;
    final String s;

    Pos(float x, float y, float w, String s) {
      this.x = x;
      this.y = y;
      this.w = w;
      this.s = s;
    }

    float right() {
      return x + w;
    }
  }

  private static List<Pos> collectPositions(PDDocument doc, int page) throws IOException {
    final List<Pos> positions = new ArrayList<>();
    PDFTextStripper stripper = new PDFTextStripper() {
      @Override
      protected void writeString(String text, List<TextPosition> textPositions) {
        for (TextPosition tp : textPositions) {
          String ch = tp.getUnicode();
          if (ch == null || ch.trim().isEmpty()) {
            continue;
          }
          positions.add(new Pos(tp.getXDirAdj(), tp.getYDirAdj(), tp.getWidthDirAdj(), ch));
        }
      }
    };
    stripper.setSortByPosition(true);
    stripper.setStartPage(page);
    stripper.setEndPage(page);
    stripper.getText(doc);
    positions.sort(Comparator.comparingDouble((Pos p) -> p.y).thenComparingDouble(p -> p.x));
    return positions;
  }

  private static void buildTransactions(List<Pos> positions, List<TransactionDTO> out) {
    List<float[]> anchors = findRowAnchors(positions);
    for (int i = 0; i < anchors.size(); i++) {
      float y = anchors.get(i)[0];
      int seqNo = (int) anchors.get(i)[1];
      // 以相邻序号的中线作为行边界：单元格折行后内容会偏离序号行 10pt 以上，
      // 原来的 ±8pt 窗口会把续行丢掉
      float yLo = i == 0 ? y - EDGE_ROW_MARGIN : (anchors.get(i - 1)[0] + y) / 2f;
      float yHi = i == anchors.size() - 1 ? y + EDGE_ROW_MARGIN : (y + anchors.get(i + 1)[0]) / 2f;

      String dateText = pick(positions, 150, 205, yLo, yHi, false);
      String timeText = pick(positions, 90, 150, yLo, yHi, false);
      String expenseText = pick(positions, 200, 255, yLo, yHi, true);
      String incomeText = pick(positions, 255, 312, yLo, yHi, true);
      String balanceText = pick(positions, 312, 367, yLo, yHi, true);
      String counterpartyAccount = pick(positions, 367, 425, yLo, yHi, false);
      String counterpartyName = pick(positions, 425, 480, yLo, yHi, false);
      String purpose = pick(positions, 530, 595, yLo, yHi, false);
      String postscript = pick(positions, 595, 640, yLo, yHi, false);
      String summaryText = pick(positions, 640, 700, yLo, yHi, false);

      LocalDate transDate = firstDate(dateText);
      if (transDate == null) {
        transDate = firstDate(timeText);
      }

      BigDecimal expense = parseAmount(expenseText);
      BigDecimal income = parseAmount(incomeText);

      TransactionDTO tx = new TransactionDTO();
      tx.setSeqNo(seqNo);
      tx.setTransDate(transDate);
      tx.setTransTime(firstTime(timeText));
      tx.setBalance(parseAmount(balanceText));
      tx.setCounterpartyAccount(clean(counterpartyAccount));
      tx.setCounterpartyName(clean(counterpartyName));
      tx.setPurpose(clean(purpose));
      tx.setPostscript(clean(postscript));
      tx.setSummary(clean(summaryText));

      // 方向由金额所在列决定；负数支出表示冲正/退款，金额取反后回流
      if (expense.signum() != 0) {
        tx.setAmount(expense.negate());
        tx.setDirection("出");
      } else if (income.signum() != 0) {
        tx.setAmount(income);
        tx.setDirection("入");
      } else {
        tx.setAmount(BigDecimal.ZERO);
        tx.setDirection("—");
      }
      tx.setAmountSignExplicit(true);
      out.add(tx);
    }
  }

  /**
   * 行锚点：序号列（x 45~90）上的连续数字，按 Y 聚合成一个整数。
   */
  private static List<float[]> findRowAnchors(List<Pos> positions) {
    List<float[]> anchors = new ArrayList<>();
    int i = 0;
    while (i < positions.size()) {
      Pos p = positions.get(i);
      if (p.x >= 45 && p.x < 90 && p.s.matches("\\d")) {
        float y = p.y;
        StringBuilder digits = new StringBuilder();
        int j = i;
        while (j < positions.size()) {
          Pos q = positions.get(j);
          if (q.x < 45 || q.x >= 90 || Math.abs(q.y - y) >= 2 || !q.s.matches("\\d")) {
            break;
          }
          digits.append(q.s);
          j++;
        }
        if (digits.length() > 0) {
          anchors.add(new float[]{y, Float.parseFloat(digits.toString())});
          i = j;
          continue;
        }
      }
      i++;
    }
    return anchors;
  }

  /**
   * 在行窗口内收集某列的字符。
   *
   * @param byRight true=按右边界匹配（金额列右对齐），false=按左边界匹配（文本列）
   */
  private static String pick(List<Pos> positions, float lo, float hi,
                             float yLo, float yHi, boolean byRight) {
    List<Pos> in = new ArrayList<>();
    for (Pos p : positions) {
      if (p.y < yLo || p.y > yHi) {
        continue;
      }
      float edge = byRight ? p.right() : p.x;
      if (edge > lo && edge <= hi) {
        in.add(p);
      }
    }
    in.sort(Comparator.comparingDouble((Pos p) -> p.y).thenComparingDouble(p -> p.x));
    StringBuilder sb = new StringBuilder();
    for (Pos p : in) {
      sb.append(p.s);
    }
    return sb.toString();
  }

  private static BigDecimal parseAmount(String text) {
    if (text == null) {
      return BigDecimal.ZERO;
    }
    String s = text.replaceAll("[^0-9.,\\-]", "");
    if (s.isEmpty() || s.equals("-") || s.equals(".")) {
      return BigDecimal.ZERO;
    }
    try {
      BigDecimal v = AmountNormalizer.normalize(s.replace(" ", ""));
      return v == null ? BigDecimal.ZERO : v;
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }

  private static LocalDate firstDate(String text) {
    if (text == null) {
      return null;
    }
    Matcher m = DATE.matcher(text);
    if (m.find()) {
      try {
        return LocalDate.parse(m.group(1));
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private static String firstTime(String text) {
    if (text == null) {
      return null;
    }
    Matcher m = TIME.matcher(text);
    if (m.find()) {
      String t = m.group(1);
      return t.length() == 7 ? "0" + t : t;
    }
    return null;
  }

  private static String clean(String text) {
    if (text == null) {
      return null;
    }
    String s = text.trim();
    return s.isEmpty() ? null : s;
  }

  // ==================== 页眉与汇总 ====================

  private static void recalcSummary(List<TransactionDTO> txs, StatementSummary s) {
    BigDecimal inc = BigDecimal.ZERO;
    BigDecimal exp = BigDecimal.ZERO;
    int ic = 0;
    int ec = 0;
    for (TransactionDTO tx : txs) {
      BigDecimal a = tx.getAmount();
      if (a == null || a.signum() == 0) {
        continue;
      }
      if (a.signum() >= 0) {
        inc = inc.add(a);
        ic++;
      } else {
        exp = exp.add(a.negate());
        ec++;
      }
    }
    s.setTotalIncome(inc);
    s.setIncomeCount(ic);
    s.setTotalExpense(exp);
    s.setExpenseCount(ec);
  }

  private static void fillHeaderOnce(String line, StatementHeader h, StatementSummary s) {
    Matcher m;
    if (h.getAccountNo() == null) {
      m = ACCOUNT.matcher(line);
      if (m.find()) {
        h.setAccountNo(m.group(1));
      }
    }
    if (h.getStartDate() == null) {
      m = DATE_RANGE.matcher(line);
      if (m.find()) {
        h.setStartDate(LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        h.setEndDate(LocalDate.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))));
      }
    }
    if (s.getIncomeCount() == null || s.getExpenseCount() == null) {
      m = COUNT_SUFFIX.matcher(line);
      if (m.find()) {
        int first = Integer.parseInt(m.group(1));
        int second = -1;
        if (m.find()) {
          second = Integer.parseInt(m.group(1));
        }
        if (s.getIncomeCount() == null && s.getExpenseCount() == null) {
          if (second > 0) {
            s.setIncomeCount(first);
            s.setExpenseCount(second);
          } else {
            s.setIncomeCount(first);
          }
        }
      }
    }
    // 收入总金额 / 支出总金额（关键字后面的金额，避免取到另一个合计）
    if (s.getTotalIncome() == null) {
      Matcher am = Pattern.compile("收入总金额\\s*([\\d,]+\\.\\d{2})").matcher(line);
      if (am.find()) {
        BigDecimal v = safeAmount(am.group(1));
        if (v != null) {
          s.setTotalIncome(v);
        }
      }
    }
    if (s.getTotalExpense() == null) {
      Matcher am = Pattern.compile("支出总金额\\s*([\\d,]+\\.\\d{2})").matcher(line);
      if (am.find()) {
        BigDecimal v = safeAmount(am.group(1));
        if (v != null) {
          s.setTotalExpense(v);
        }
      }
    }
  }

  private static void fillHeaderFallback(String text, StatementHeader h, StatementSummary s) {
    if (h.getAccountNo() == null) {
      Matcher m = ACCOUNT_NUM.matcher(text);
      if (m.find()) {
        h.setAccountNo(m.group(1));
      }
    }
    if (h.getAccountHolder() == null && h.getAccountNo() != null) {
      int idx = text.indexOf(h.getAccountNo());
      if (idx >= 0) {
        Matcher cn = Pattern.compile("[\\u4e00-\\u9fa5]{4,}").matcher(text.substring(idx + h.getAccountNo().length()));
        if (cn.find()) {
          h.setAccountHolder(cn.group());
        }
      }
    }
    if (h.getStartDate() == null) {
      Matcher dm = ANY_DATE.matcher(text);
      LocalDate first = null;
      LocalDate second = null;
      while (dm.find()) {
        LocalDate d = parseDate(dm.group(1));
        if (d == null) {
          continue;
        }
        if (first == null) {
          first = d;
        } else {
          second = d;
          break;
        }
      }
      if (first != null) {
        h.setStartDate(first);
      }
      if (second != null) {
        h.setEndDate(second);
      }
    }
  }

  private static BigDecimal safeAmount(String s) {
    try {
      return AmountNormalizer.normalize(s);
    } catch (Exception e) {
      return null;
    }
  }

  private static LocalDate parseDate(String s) {
    try {
      String clean = s.replace("年", "-").replace("月", "-").replace("日", "");
      String[] p = clean.split("-");
      if (p.length >= 3) {
        return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
      }
    } catch (Exception ignored) {
    }
    return null;
  }
}
