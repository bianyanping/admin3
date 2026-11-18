package tech.wetech.admin3.ocrApi.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信支付交易明细证明（财付通）原生 PDF 解析器。
 *
 * <p>版式（一份证明通常 1 页表头 + N 页明细，除第 1 页外不重复表头）：
 * <pre>
 *   微信支付交易明细证明
 *   兹证明：李秋龙（居民身份证：4521…），在其微信号：wxid_xxx 中的交易明细信息如下：
 *   交易单号 交易时间 交易类型 收/支/其他 交易方式 金额(元) 交易对方 商户单号
 *   1041304355871790869891012026-10-01 经营收款 收入 / 30.88 赵*0 /
 *   92480101 23:51:36
 * </pre>
 *
 * <p>难点与对策：
 * <ol>
 *   <li><b>每笔占两行</b>：主行放单号前段+日期+类型+金额等，次行放单号后段和 HH:mm:ss；
 *       交易对方过长时会折成多行（最多见过 7 行）。因此不能用「相邻行中线」切分，
 *       改为以「收/支/其他」单元格为行锚点，其下、下一个锚点之上的内容都归属该笔。</li>
 *   <li><b>单号与日期被并成一段</b>（如 {@code 1041304355871790869891012026-10-01}），
 *       先把日期摘出来，剩下的数字再和次行的单号尾段拼接。</li>
 *   <li><b>列边界不能用表头文字中心</b>：交易对方文案会压到「商户单号」表头起点左侧
 *       （文字中心法会把「/」判给对方列）。这里改用表头文字的左边界取中点，按文本段左边界归列。</li>
 * </ol>
 *
 * <p>微信没有「账户余额」「对方账号」两列，落库时保持 null；交易单号/商户单号并入备注。
 * 「其他」类（零钱提现/充值、信用卡还款等）保留原始方向 rawDirection=其他，
 * 并按业务口径映射为入/出用于统计；未覆盖的中性摘要保留「其他」并记录日志。
 */
public final class WechatNativePdfParser {

  private static final Logger LOG = LoggerFactory.getLogger(WechatNativePdfParser.class);

  private static final Pattern NAME = Pattern.compile("兹证明[：:]\\s*([^（(]+)[（(]");
  private static final Pattern WXID = Pattern.compile("微信号[：:]\\s*([A-Za-z0-9_-]+)");
  private static final Pattern DATE_RANGE = Pattern.compile(
    "(\\d{4}-\\d{2}-\\d{2})\\s+\\d{2}:\\d{2}:\\d{2}\\s*至\\s*(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern TIME = Pattern.compile("(\\d{1,2}:\\d{2}:\\d{2})");
  private static final Pattern IO = Pattern.compile("收入|支出|其他");
  private static final Pattern FOOTER = Pattern.compile("^说明[：:]?$");
  private static final Pattern BLANKS = Pattern.compile("[\\s\\u00A0\\u3000]+");

  /** 表头文字 → 字段名 */
  private static final Map<String, String> HEADERS = new LinkedHashMap<>();

  static {
    HEADERS.put("交易单号", "serial");
    HEADERS.put("交易时间", "time");
    HEADERS.put("交易类型", "type");
    HEADERS.put("收/支/其他", "io");
    HEADERS.put("收／支／其他", "io");
    HEADERS.put("交易方式", "method");
    HEADERS.put("金额(元)", "amount");
    HEADERS.put("金额（元）", "amount");
    HEADERS.put("交易对方", "counterparty");
    HEADERS.put("商户单号", "merchant");
  }

  /** 表头所在行的最少标签数：正文前部的「交易类型：全部账单」只有 2 个标签，靠数量区分 */
  private static final int MIN_HEADER_LABELS = 6;
  /** 行锚点容差（pt） */
  private static final float ANCHOR_TOLERANCE = 2f;

  private WechatNativePdfParser() {
  }

  public static BankStatementDTO parse(File pdfFile) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();

    header.setBankName("微信支付");
    header.setCurrency("人民币");

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      String firstPageText = firstPageText(doc);
      fillHeader(firstPageText, header);

      List<List<Run>> pages = collectRuns(doc);
      Columns columns = detectColumns(pages.isEmpty() ? List.of() : pages.get(0));
      if (columns == null) {
        throw new IOException("微信支付交易明细证明：未识别到表头（交易单号/交易时间/收-支-其他/金额(元) 等）");
      }
      for (List<Run> page : pages) {
        transactions.addAll(extractPage(page, columns));
      }
    }

    for (int i = 0; i < transactions.size(); i++) {
      transactions.get(i).setSeqNo(i + 1);
    }
    recalcSummary(transactions, summary);

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    // 940 页证明的全文有 200 万字符，不再重复留存
    dto.setRawOcrText(null);
    return dto;
  }

  // ==================== 页眉 ====================

  private static String firstPageText(PDDocument doc) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);
    stripper.setStartPage(1);
    stripper.setEndPage(1);
    return stripper.getText(doc);
  }

  private static void fillHeader(String text, StatementHeader header) {
    if (text == null) {
      return;
    }
    Matcher m = NAME.matcher(text);
    if (m.find()) {
      header.setAccountHolder(m.group(1).trim());
    }
    m = WXID.matcher(text);
    if (m.find()) {
      header.setAccountNo(m.group(1));
    }
    m = DATE_RANGE.matcher(text);
    if (m.find()) {
      header.setStartDate(parseDate(m.group(1)));
      header.setEndDate(parseDate(m.group(2)));
    }
  }

  private static LocalDate parseDate(String s) {
    try {
      return LocalDate.parse(s);
    } catch (Exception e) {
      return null;
    }
  }

  // ==================== 坐标采集 ====================

  private static final class Run {
    final String text;
    final float x0;
    final float y;

    Run(String text, float x0, float y) {
      this.text = text;
      this.x0 = x0;
      this.y = y;
    }
  }

  private static List<List<Run>> collectRuns(PDDocument doc) throws IOException {
    int pageCount = doc.getNumberOfPages();
    List<List<Run>> pages = new ArrayList<>(pageCount);
    for (int i = 0; i < pageCount; i++) {
      pages.add(new ArrayList<>());
    }
    PDFTextStripper stripper = new PDFTextStripper() {
      @Override
      protected void writeString(String text, List<TextPosition> positions) {
        int pageNo = getCurrentPageNo() - 1;
        if (positions == null || positions.isEmpty() || pageNo < 0 || pageNo >= pages.size()) {
          return;
        }
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
          return;
        }
        pages.get(pageNo).add(new Run(value, positions.get(0).getXDirAdj(), positions.get(0).getYDirAdj()));
      }
    };
    stripper.setSortByPosition(true);
    stripper.setStartPage(1);
    stripper.setEndPage(pageCount);
    stripper.getText(doc);
    Comparator<Run> byPosition = Comparator.comparingDouble((Run r) -> r.y).thenComparingDouble(r -> r.x0);
    for (List<Run> page : pages) {
      page.sort(byPosition);
    }
    return pages;
  }

  private static final class Columns {
    final List<String> fields = new ArrayList<>();
    final List<Float> boundaries = new ArrayList<>();

    String fieldAt(float x) {
      for (int i = 0; i < boundaries.size(); i++) {
        if (x < boundaries.get(i)) {
          return fields.get(i);
        }
      }
      return fields.get(fields.size() - 1);
    }
  }

  /**
   * 表头行 = 一页中命中标签数最多的那一行；列边界取相邻标签左边界的中点。
   */
  private static Columns detectColumns(List<Run> page) {
    Map<Float, Map<String, Float>> lines = new LinkedHashMap<>();
    for (Run run : page) {
      String field = HEADERS.get(run.text);
      if (field == null) {
        continue;
      }
      Float key = null;
      for (Float y : lines.keySet()) {
        if (Math.abs(y - run.y) <= 1f) {
          key = y;
          break;
        }
      }
      if (key == null) {
        key = run.y;
      }
      lines.computeIfAbsent(key, k -> new LinkedHashMap<>()).putIfAbsent(field, run.x0);
    }
    Float headerY = null;
    int best = 0;
    for (Map.Entry<Float, Map<String, Float>> entry : lines.entrySet()) {
      if (entry.getValue().size() > best) {
        best = entry.getValue().size();
        headerY = entry.getKey();
      }
    }
    if (headerY == null || best < MIN_HEADER_LABELS) {
      return null;
    }
    Map<String, Float> labels = lines.get(headerY);
    List<Map.Entry<String, Float>> sorted = new ArrayList<>(labels.entrySet());
    sorted.sort(Map.Entry.comparingByValue());

    Columns columns = new Columns();
    for (int i = 0; i < sorted.size(); i++) {
      columns.fields.add(sorted.get(i).getKey());
      if (i > 0) {
        columns.boundaries.add((sorted.get(i - 1).getValue() + sorted.get(i).getValue()) / 2f);
      }
    }
    return columns;
  }

  // ==================== 行重建 ====================

  private static List<TransactionDTO> extractPage(List<Run> runs, Columns columns) {
    List<Run> anchors = new ArrayList<>();
    for (Run run : runs) {
      if ("io".equals(columns.fieldAt(run.x0)) && IO.matcher(run.text).matches()) {
        anchors.add(run);
      }
    }
    if (anchors.isEmpty()) {
      return List.of();
    }
    anchors.sort(Comparator.comparingDouble(r -> r.y));

    float dataTop = anchors.get(0).y - ANCHOR_TOLERANCE;
    float dataBottom = Float.MAX_VALUE;
    for (Run run : runs) {
      // 末页页脚「说明：」在左端起始，截断其后的法律说明与盖章
      if (run.y > anchors.get(anchors.size() - 1).y
        && "serial".equals(columns.fieldAt(run.x0))
        && FOOTER.matcher(run.text).matches()) {
        dataBottom = Math.min(dataBottom, run.y);
      }
    }

    List<Map<String, StringBuilder>> cells = new ArrayList<>(anchors.size());
    for (int i = 0; i < anchors.size(); i++) {
      cells.add(new LinkedHashMap<>());
    }
    for (Run run : runs) {
      if (run.y < dataTop - ANCHOR_TOLERANCE || run.y >= dataBottom) {
        continue;
      }
      int row = rowOf(anchors, run.y);
      if (row < 0) {
        continue;
      }
      String field = columns.fieldAt(run.x0);
      cells.get(row).computeIfAbsent(field, k -> new StringBuilder()).append(run.text);
    }

    List<TransactionDTO> result = new ArrayList<>(anchors.size());
    for (Map<String, StringBuilder> cell : cells) {
      TransactionDTO tx = buildTransaction(cell);
      if (tx != null) {
        result.add(tx);
      }
    }
    return result;
  }

  /** 返回该 y 所属的行：最后一个 y 不大于它的锚点 */
  private static int rowOf(List<Run> anchors, float y) {
    int row = -1;
    for (int i = 0; i < anchors.size(); i++) {
      if (anchors.get(i).y <= y + ANCHOR_TOLERANCE) {
        row = i;
      } else {
        break;
      }
    }
    return row;
  }

  private static TransactionDTO buildTransaction(Map<String, StringBuilder> cell) {
    String io = text(cell.get("io"));
    if (io == null) {
      return null;
    }
    String serialRaw = text(cell.get("serial"));
    String timeRaw = text(cell.get("time"));

    String dateText = null;
    if (serialRaw != null) {
      Matcher dm = DATE.matcher(serialRaw);
      if (dm.find()) {
        dateText = dm.group(1);
        serialRaw = serialRaw.substring(0, dm.start()) + serialRaw.substring(dm.end());
      }
    }
    if (dateText == null && timeRaw != null) {
      Matcher dm = DATE.matcher(timeRaw);
      if (dm.find()) {
        dateText = dm.group(1);
      }
    }
    LocalDate transDate = parseDate(dateText);

    String transTime = null;
    if (timeRaw != null) {
      Matcher tm = TIME.matcher(timeRaw);
      if (tm.find()) {
        String t = tm.group(1);
        transTime = t.length() == 7 ? "0" + t : t;
      }
    }

    BigDecimal amount = parseAmount(text(cell.get("amount")));
    if (amount == null) {
      return null;
    }

    TransactionDTO tx = new TransactionDTO();
    tx.setTransDate(transDate);
    tx.setTransTime(transTime);
    if (transDate != null && transTime != null) {
      try {
        tx.setTransDateTime(LocalDateTime.of(transDate, LocalTime.parse(transTime)));
      } catch (Exception ignored) {
      }
    }
    tx.setSummary(text(cell.get("type")));
    tx.setChannel(text(cell.get("method")));
    tx.setCounterpartyName(cleanCounterparty(text(cell.get("counterparty"))));
    tx.setAmountSignExplicit(true);
    // 微信证明没有余额、对方账号两列
    tx.setBalance(null);
    tx.setCounterpartyAccount(null);
    tx.setRemark(buildRemark(serialRaw, text(cell.get("merchant"))));

    if ("支出".equals(io)) {
      tx.setAmount(amount.negate());
      tx.setDirection("出");
    } else if ("收入".equals(io)) {
      tx.setAmount(amount);
      tx.setDirection("入");
    } else {
      // 微信「其他」：保留数据源原值，并按摘要映射为业务口径的出/入
      tx.setRawDirection(io);
      String summary = tx.getSummary();
      String mapped = mapOtherDirection(summary);
      if ("出".equals(mapped)) {
        tx.setAmount(amount.negate());
        tx.setDirection("出");
      } else if ("入".equals(mapped)) {
        tx.setAmount(amount);
        tx.setDirection("入");
      } else {
        // 未覆盖的中性摘要不猜测，保留「其他」并记录
        tx.setAmount(amount);
        tx.setDirection("其他");
        LOG.warn("微信「其他」类出现未覆盖摘要，保留 direction=其他：summary={}, 金额={}", summary, amount);
      }
    }
    return tx;
  }

  /**
   * 微信证明「其他」类摘要的业务方向映射。
   * 出：信用卡还款、经营账户提现、零钱提现、零钱通转出-到零钱；
   * 入：零钱充值、零钱通转入；其余返回 null（保留「其他」）。
   */
  private static String mapOtherDirection(String summary) {
    if (summary == null) {
      return null;
    }
    if (summary.contains("信用卡还款")
      || summary.contains("经营账户提现")
      || summary.contains("零钱提现")
      || summary.contains("零钱通转出")) {
      return "出";
    }
    if (summary.contains("零钱充值") || summary.contains("零钱通转入")) {
      return "入";
    }
    return null;
  }

  private static String buildRemark(String serial, String merchant) {
    StringBuilder sb = new StringBuilder();
    if (serial != null) {
      sb.append("交易单号：").append(serial.replaceAll("\\s+", ""));
    }
    if (merchant != null) {
      if (sb.length() > 0) {
        sb.append("；");
      }
      sb.append("商户单号：").append(merchant.replaceAll("\\s+", ""));
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  private static String text(StringBuilder sb) {
    if (sb == null) {
      return null;
    }
    String value = BLANKS.matcher(sb.toString()).replaceAll(" ").trim();
    return value.isEmpty() || "/".equals(value) ? null : value;
  }

  /**
   * 对方户名为单字脱敏（如「*」）时，商户单号列的占位「/」会被并进同一文本段，需剥掉。
   */
  private static String cleanCounterparty(String value) {
    if (value == null) {
      return null;
    }
    String cleaned = value.replaceFirst("\\s+/$", "").trim();
    return cleaned.isEmpty() || "/".equals(cleaned) ? null : cleaned;
  }

  private static BigDecimal parseAmount(String text) {
    if (text == null) {
      return null;
    }
    String s = text.replaceAll("[^0-9.,]", "");
    if (s.isEmpty()) {
      return null;
    }
    try {
      return AmountNormalizer.normalize(s);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * 汇总按业务口径统计：direction 已把微信「其他」按摘要映射为入/出，逐笔累加。
   * 因此本单合计会与微信证明「其他不计收支」的口径存在差额，属预期。
   */
  private static void recalcSummary(List<TransactionDTO> transactions, StatementSummary summary) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expense = BigDecimal.ZERO;
    int incomeCount = 0;
    int expenseCount = 0;
    for (TransactionDTO tx : transactions) {
      if (tx.getAmount() == null) {
        continue;
      }
      if ("入".equals(tx.getDirection())) {
        income = income.add(tx.getAmount());
        incomeCount++;
      } else if ("出".equals(tx.getDirection())) {
        expense = expense.add(tx.getAmount().abs());
        expenseCount++;
      }
    }
    summary.setTotalIncome(income);
    summary.setIncomeCount(incomeCount);
    summary.setTotalExpense(expense);
    summary.setExpenseCount(expenseCount);
  }
}
