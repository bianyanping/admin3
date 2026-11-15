package tech.wetech.admin3.ocrApi.strategy;

import org.json.JSONArray;
import org.json.JSONObject;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.AmountNormalizer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 广西农商联合银行「账户交易明细」表格型 OCR（tables_result）解析器。
 *
 * <p>每页一张表，12 列：序号 / 交易时间 / 支出收入金额 / 账户余额 /
 * 对方账号 / 对方户名 / 交易渠道 / 用途 / 摘要 / 摘要内容 / 附言 / 备注。
 */
public class GxrcbTableStatementParser implements BankStatementParserStrategy {

  private static final Pattern DATE_PATTERN =
    Pattern.compile("(\\d{4})[-年]?(\\d{1,2})[-月]?(\\d{1,2})日?");
  private static final Pattern YMD_PATTERN =
    Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern TIME_PATTERN =
    Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})");
  private static final Pattern COUNT_PATTERN = Pattern.compile("\\d+");

  private static final Map<String, Integer> DEFAULT_COLUMNS = Map.ofEntries(
    Map.entry("seqNo", 0),
    Map.entry("transTime", 1),
    Map.entry("amount", 2),
    Map.entry("balance", 3),
    Map.entry("counterpartyAccount", 4),
    Map.entry("counterpartyName", 5),
    Map.entry("channel", 6),
    Map.entry("purpose", 7),
    Map.entry("summary", 8),
    Map.entry("summaryContent", 9),
    Map.entry("postscript", 10),
    Map.entry("remark", 11)
  );

  @Override
  public String bankCode() {
    return "GXRCB";
  }

  @Override
  public int priority() {
    return 10;
  }

  @Override
  public boolean supports(ParseContext context) {
    if ("GXRCB".equalsIgnoreCase(context.getExplicitBankCode())) {
      return true;
    }
    List<String> words = context.getAllWords();
    boolean singleAmountColumn = words.stream().anyMatch(w -> w.contains("支出/收入金额"));
    boolean detail = words.stream().anyMatch(w -> w.contains("账户交易明细"));
    return singleAmountColumn && detail;
  }

  @Override
  public BankStatementDTO parse(JSONArray pages) {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    header.setBankName("广西农商联合银行");
    dto.setHeader(header);

    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.getJSONObject(p);
      JSONArray tables = page.optJSONArray("tables_result");
      if (tables == null) {
        continue;
      }
      for (int t = 0; t < tables.length(); t++) {
        JSONObject table = tables.getJSONObject(t);
        fillHeaderFromWords(header, table.optJSONArray("header"));
        fillSummaryFromWords(summary, table.optJSONArray("footer"));
        List<Cell> cells = collectBodyCells(table);
        appendRaw(raw, table, cells);
        if (isTransactionTable(cells)) {
          parseTransactions(transactions, cells);
        }
      }
    }

    fillMissingSummary(summary, transactions);
    dto.setTransactions(transactions);
    dto.setSummary(summary);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  // ---------- 表头 / 汇总 ----------

  private void fillHeaderFromWords(StatementHeader header, JSONArray words) {
    if (words == null) {
      return;
    }
    for (int i = 0; i < words.length(); i++) {
      String text = words.getJSONObject(i).optString("words", "").trim();
      if (text.startsWith("户名")) {
        header.setAccountHolder(valueAfterLabel(text, "户名"));
      } else if (text.startsWith("账号") || text.startsWith("卡号")) {
        header.setAccountNo(valueAfterLabel(text, text.startsWith("账号") ? "账号" : "卡号"));
      } else if (text.startsWith("日期")) {
        fillDateRange(header, valueAfterLabel(text, "日期"));
      }
    }
  }

  private void fillSummaryFromWords(StatementSummary summary, JSONArray words) {
    if (words == null) {
      return;
    }
    for (int i = 0; i < words.length(); i++) {
      String text = words.getJSONObject(i).optString("words", "").trim();
      if (text.startsWith("收入合计")) {
        summary.setTotalIncome(parseAmount(valueAfterLabel(text, "收入合计")));
      } else if (text.startsWith("收入笔数")) {
        summary.setIncomeCount(parseCount(valueAfterLabel(text, "收入笔数")));
      } else if (text.startsWith("支出合计")) {
        summary.setTotalExpense(parseAmount(valueAfterLabel(text, "支出合计")));
      } else if (text.startsWith("支出笔数")) {
        summary.setExpenseCount(parseCount(valueAfterLabel(text, "支出笔数")));
      } else if (text.contains("明细申请时间")) {
        summary.setApplyTime(valueAfterLabel(text, "明细申请时间"));
      }
    }
  }

  /** 银行页尾没打印支出合计时，用解析出的交易补全 */
  private void fillMissingSummary(StatementSummary summary, List<TransactionDTO> transactions) {
    BigDecimal expense = BigDecimal.ZERO;
    BigDecimal income = BigDecimal.ZERO;
    int expenseCount = 0;
    int incomeCount = 0;
    for (TransactionDTO tx : transactions) {
      BigDecimal amount = tx.getAmount();
      if (amount == null || amount.signum() == 0) {
        continue;
      }
      if (amount.signum() < 0) {
        expense = expense.add(amount.negate());
        expenseCount++;
      } else {
        income = income.add(amount);
        incomeCount++;
      }
    }
    if (summary.getTotalExpense() == null) {
      summary.setTotalExpense(expense);
      summary.setExpenseCount(expenseCount);
    }
    if (summary.getTotalIncome() == null) {
      summary.setTotalIncome(income);
      summary.setIncomeCount(incomeCount);
    }
  }

  private void fillDateRange(StatementHeader header, String range) {
    if (range == null) {
      return;
    }
    Matcher m = DATE_PATTERN.matcher(range);
    if (m.find()) {
      header.setStartDate(toDate(m));
    }
    if (m.find()) {
      header.setEndDate(toDate(m));
    }
  }

  // ---------- 交易明细 ----------

  private List<Cell> collectBodyCells(JSONObject table) {
    List<Cell> cells = new ArrayList<>();
    JSONArray body = table.optJSONArray("body");
    if (body == null) {
      return cells;
    }
    for (int i = 0; i < body.length(); i++) {
      JSONObject obj = body.getJSONObject(i);
      String words = obj.optString("words", "").trim();
      if (words.isEmpty()) {
        continue;
      }
      cells.add(new Cell(obj.optInt("row_start", 0), obj.optInt("col_start", 0), words));
    }
    return cells;
  }

  private TreeMap<Integer, TreeMap<Integer, String>> toRows(List<Cell> cells) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();
    for (Cell c : cells) {
      rows.computeIfAbsent(c.row, k -> new TreeMap<>()).put(c.col, c.words);
    }
    return rows;
  }

  private boolean isTransactionTable(List<Cell> cells) {
    return cells.stream().anyMatch(c -> c.words.contains("序号"))
      && cells.stream().anyMatch(c -> c.words.contains("支出/收入金额"));
  }

  private void parseTransactions(List<TransactionDTO> out, List<Cell> cells) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = toRows(cells);
    if (rows.isEmpty()) {
      return;
    }

    Map<String, Integer> columns = new LinkedHashMap<>(DEFAULT_COLUMNS);
    int firstDataRow = rows.firstKey();
    for (Map.Entry<Integer, TreeMap<Integer, String>> rowEntry : rows.entrySet()) {
      Map<Integer, String> detected = new LinkedHashMap<>();
      for (Map.Entry<Integer, String> e : rowEntry.getValue().entrySet()) {
        String field = resolveHeaderField(e.getValue());
        if (field != null) {
          detected.put(e.getKey(), field);
        }
      }
      if (detected.containsValue("seqNo") && detected.containsValue("amount")) {
        columns.clear();
        detected.forEach((col, field) -> columns.put(field, col));
        firstDataRow = rowEntry.getKey() + 1; // 表头行不参与交易
        break;
      }
    }

    for (Map.Entry<Integer, TreeMap<Integer, String>> rowEntry : rows.entrySet()) {
      if (rowEntry.getKey() < firstDataRow) {
        continue;
      }
      TreeMap<Integer, String> row = rowEntry.getValue();
      String seq = value(row, "seqNo", columns);
      if (seq == null || !COUNT_PATTERN.matcher(seq.trim()).matches()) {
        continue;
      }
      TransactionDTO tx = new TransactionDTO();
      tx.setSeqNo(Integer.parseInt(seq.trim()));

      String timeCell = value(row, "transTime", columns);
      tx.setTransDate(parseDate(timeCell));
      tx.setTransTime(parseTime(timeCell));
      if (tx.getTransDate() != null && tx.getTransTime() != null) {
        tx.setTransDateTime(LocalDateTime.of(tx.getTransDate(),
          LocalTime.parse(tx.getTransTime())));
      }

      String rawAmount = value(row, "amount", columns);
      BigDecimal amount = parseAmount(rawAmount);
      tx.setAmount(amount);
      if (amount != null && amount.signum() != 0) {
        tx.setDirection(amount.signum() < 0 ? "出" : "入");
      }
      tx.setAmountSignExplicit(rawAmount != null
        && (rawAmount.trim().startsWith("+") || rawAmount.trim().startsWith("-")));
      tx.setBalance(parseAmount(value(row, "balance", columns)));

      tx.setCounterpartyAccount(normalizeAccount(
        value(row, "counterpartyAccount", columns)));
      tx.setCounterpartyName(joinText(value(row, "counterpartyName", columns)));
      tx.setChannel(joinText(value(row, "channel", columns)));
      tx.setPurpose(joinText(value(row, "purpose", columns)));
      tx.setSummary(joinText(value(row, "summary", columns)));
      tx.setPostscript(joinText(value(row, "postscript", columns)));
      tx.setRemark(joinText(value(row, "remark", columns)));

      out.add(tx);
    }
  }

  private String resolveHeaderField(String text) {
    if (text == null) {
      return null;
    }
    String clean = text.replaceAll("\\s+", "");
    if (clean.contains("摘要内容")) {
      return "summaryContent";
    }
    if (clean.equals("序号")) {
      return "seqNo";
    }
    if (clean.equals("交易时间")) {
      return "transTime";
    }
    if (clean.contains("支出") && clean.contains("收入") && clean.contains("金额")) {
      return "amount";
    }
    if (clean.equals("账户余额") || clean.equals("余额")) {
      return "balance";
    }
    if (clean.equals("对方账号")) {
      return "counterpartyAccount";
    }
    if (clean.equals("对方户名")) {
      return "counterpartyName";
    }
    if (clean.equals("交易渠道")) {
      return "channel";
    }
    if (clean.equals("用途")) {
      return "purpose";
    }
    if (clean.equals("摘要")) {
      return "summary";
    }
    if (clean.equals("附言")) {
      return "postscript";
    }
    if (clean.equals("备注")) {
      return "remark";
    }
    return null;
  }

  // ---------- 字段清洗 ----------

  private String value(TreeMap<Integer, String> row, String field,
                       Map<String, Integer> columns) {
    Integer col = columns.get(field);
    return col == null ? null : row.get(col);
  }

  private String valueAfterLabel(String text, String label) {
    String rest = text.substring(label.length());
    rest = rest.replaceFirst("^[：:\\s]+", "").trim();
    return rest.isEmpty() ? null : rest;
  }

  private LocalDate parseDate(String s) {
    if (s == null) {
      return null;
    }
    Matcher m = DATE_PATTERN.matcher(s);
    return m.find() ? toDate(m) : null;
  }

  private LocalDate toDate(Matcher m) {
    return LocalDate.of(Integer.parseInt(m.group(1)),
      Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
  }

  private String parseTime(String s) {
    if (s == null) {
      return null;
    }
    String flat = s.replaceAll("\\s+", "");
    Matcher dateMatcher = YMD_PATTERN.matcher(flat);
    if (dateMatcher.lookingAt()) {
      Matcher timeMatcher = TIME_PATTERN.matcher(flat.substring(dateMatcher.end()));
      if (timeMatcher.lookingAt()) {
        return formatTime(timeMatcher);
      }
    }
    Matcher m = TIME_PATTERN.matcher(s);
    return m.find() ? formatTime(m) : null;
  }

  private String formatTime(Matcher m) {
    return String.format("%02d:%s:%s", Integer.parseInt(m.group(1)),
      m.group(2), m.group(3));
  }

  private BigDecimal parseAmount(String s) {
    if (s == null) {
      return null;
    }
    String cleaned = s.replaceAll("[^0-9.,+-]", "");
    if (cleaned.isEmpty() || !cleaned.matches("[+-]?[\\d.,]+")) {
      return null;
    }
    try {
      return AmountNormalizer.normalize(cleaned);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private Integer parseCount(String s) {
    if (s == null) {
      return null;
    }
    Matcher m = COUNT_PATTERN.matcher(s);
    return m.find() ? Integer.parseInt(m.group()) : null;
  }

  private String joinText(String s) {
    if (s == null) {
      return null;
    }
    String cleaned = s.replaceAll("\\s+", "").trim();
    return cleaned.isEmpty() ? null : cleaned;
  }

  private String normalizeAccount(String s) {
    String cleaned = joinText(s);
    if (cleaned == null || !cleaned.matches("[0-9*]{6,}")) {
      return null;
    }
    return cleaned;
  }

  private void appendRaw(StringBuilder raw, JSONObject table, List<Cell> cells) {
    JSONArray header = table.optJSONArray("header");
    if (header != null) {
      for (int i = 0; i < header.length(); i++) {
        raw.append(header.getJSONObject(i).optString("words", "")).append('|');
      }
    }
    for (Cell c : cells) {
      raw.append(c.words.replace('\n', ' ')).append('|');
    }
    JSONArray footer = table.optJSONArray("footer");
    if (footer != null) {
      for (int i = 0; i < footer.length(); i++) {
        raw.append(footer.getJSONObject(i).optString("words", "")).append('|');
      }
    }
    raw.append('\n');
  }

  private static final class Cell {
    private final int row;
    private final int col;
    private final String words;

    private Cell(int row, int col, String words) {
      this.row = row;
      this.col = col;
      this.words = words;
    }
  }
}
