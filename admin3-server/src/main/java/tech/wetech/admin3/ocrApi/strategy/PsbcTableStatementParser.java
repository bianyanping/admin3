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
 * 中国邮政储蓄银行「账户交易明细专用回单」表格型 OCR（tables_result）解析器。
 *
 * <p>邮储回单返回的是表格结构：每页 tables_result 中，
 * 一张表是账号/户名/汇总信息，另一张表是 14 列交易明细。
 * 这里直接按表格行列取值，避免把多行单元格拼成词流后再猜列。
 */
public class PsbcTableStatementParser implements BankStatementParserStrategy {

  private static final Pattern DATE_PATTERN =
    Pattern.compile("(\\d{4})[-年]?(\\d{1,2})[-月]?(\\d{1,2})日?");
  private static final Pattern YMD_PATTERN =
    Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern TIME_PATTERN =
    Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})");
  private static final Pattern COUNT_PATTERN = Pattern.compile("\\d+");

  /** 邮储交易明细的固定列顺序（表头缺失的续页也按此顺序取值） */
  private static final Map<String, Integer> DEFAULT_COLUMNS = Map.ofEntries(
    Map.entry("seqNo", 0),
    Map.entry("transTime", 1),
    Map.entry("transDate", 2),
    Map.entry("expense", 3),
    Map.entry("income", 4),
    Map.entry("balance", 5),
    Map.entry("counterpartyAccount", 6),
    Map.entry("counterpartyName", 7),
    Map.entry("counterpartyBank", 8),
    Map.entry("purpose", 9),
    Map.entry("postscript", 10),
    Map.entry("summary", 11),
    Map.entry("serialNo", 12),
    Map.entry("routeNo", 13)
  );

  private static final Map<String, String> HEADER_COLUMN_NAMES = Map.ofEntries(
    Map.entry("序号", "seqNo"),
    Map.entry("交易时间", "transTime"),
    Map.entry("记账日期", "transDate"),
    Map.entry("支出金额", "expense"),
    Map.entry("收入金额", "income"),
    Map.entry("余额", "balance"),
    Map.entry("对方账号", "counterpartyAccount"),
    Map.entry("对方户名", "counterpartyName"),
    Map.entry("对方行名", "counterpartyBank"),
    Map.entry("用途", "purpose"),
    Map.entry("附言", "postscript"),
    Map.entry("摘要", "summary"),
    Map.entry("交易流水号", "serialNo"),
    Map.entry("全局路由号", "routeNo")
  );

  @Override
  public String bankCode() {
    return "PSBC";
  }

  @Override
  public int priority() {
    return 10;
  }

  @Override
  public boolean supports(ParseContext context) {
    if ("PSBC".equalsIgnoreCase(context.getExplicitBankCode())) {
      return true;
    }
    return context.getAllWords().stream()
      .anyMatch(w -> w.contains("中国邮政储蓄") || w.contains("POSTAL SAVINGS"));
  }

  @Override
  public BankStatementDTO parse(JSONArray pages) {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    header.setBankName("中国邮政储蓄银行");
    dto.setHeader(header);

    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.getJSONObject(p);
      JSONArray tables = page.optJSONArray("tables_result");
      if (tables == null) {
        continue; // OCR 限流空页，跳过
      }
      for (int t = 0; t < tables.length(); t++) {
        JSONObject table = tables.getJSONObject(t);
        List<Cell> cells = collectBodyCells(table);
        appendRaw(raw, table, cells);
        if (cells.isEmpty()) {
          continue;
        }
        if (isAccountInfoTable(cells)) {
          fillHeaderAndSummary(header, summary, cells);
        } else if (isTransactionTable(cells)) {
          parseTransactions(transactions, cells);
        }
      }
    }

    dto.setTransactions(transactions);
    dto.setSummary(summary);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  // ---------- 表格读取 ----------

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

  private TreeMap<Integer, TreeMap<Integer, String>> toRows(List<Cell> cells) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();
    for (Cell c : cells) {
      rows.computeIfAbsent(c.row, k -> new TreeMap<>()).put(c.col, c.words);
    }
    return rows;
  }

  private String value(TreeMap<Integer, String> row, String field,
                       Map<String, Integer> columns) {
    Integer col = columns.get(field);
    return col == null ? null : row.get(col);
  }

  // ---------- 表头 / 汇总 ----------

  private boolean isAccountInfoTable(List<Cell> cells) {
    boolean hasAccount = cells.stream().anyMatch(c -> c.words.startsWith("账号"));
    boolean hasHolder = cells.stream().anyMatch(c -> c.words.startsWith("户名"));
    return hasAccount && hasHolder;
  }

  private void fillHeaderAndSummary(StatementHeader header, StatementSummary summary,
                                    List<Cell> cells) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = toRows(cells);
    for (TreeMap<Integer, String> row : rows.values()) {
      for (Map.Entry<Integer, String> entry : row.entrySet()) {
        String text = entry.getValue();
        String next = row.get(entry.getKey() + 1);

        if (text.startsWith("账号")) {
          header.setAccountNo(joinText(next));
        } else if (text.startsWith("户名")) {
          header.setAccountHolder(joinText(next));
        } else if (text.startsWith("起止日期")) {
          fillDateRange(header, next);
        } else if (text.startsWith("收入总金额")) {
          summary.setTotalIncome(parseAmount(next));
          // 该行第 4 列是支出总金额，OCR 有时会漏掉"支出总金额"标签
          BigDecimal paired = parseAmount(row.get(3));
          if (paired != null) {
            summary.setTotalExpense(paired);
          }
        } else if (text.startsWith("支出总金额")) {
          summary.setTotalExpense(parseAmount(next));
        } else if (text.startsWith("收入总笔数")) {
          summary.setIncomeCount(parseCount(next));
          Integer paired = parseCount(row.get(3));
          if (paired != null) {
            summary.setExpenseCount(paired);
          }
        } else if (text.startsWith("支出总笔数") || text.startsWith("支出总第数")) {
          summary.setExpenseCount(parseCount(next));
        } else if (text.contains("导出时间")) {
          summary.setApplyTime(joinText(next));
        }
      }
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

  private boolean isTransactionTable(List<Cell> cells) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = toRows(cells);
    if (rows.isEmpty()) {
      return false;
    }
    TreeMap<Integer, String> firstRow = rows.firstEntry().getValue();
    boolean hasHeader = firstRow.values().stream()
      .anyMatch(w -> w.startsWith("序号") || w.startsWith("交易时间"));
    if (hasHeader) {
      return true;
    }
    // 续页没有表头：首行以序号开头，且第 4/5 列是金额
    String seq = firstRow.getOrDefault(0, "");
    return COUNT_PATTERN.matcher(seq).matches()
      && firstRow.getOrDefault(3, "").matches("[\\d,.]+")
      && firstRow.getOrDefault(4, "").matches("[\\d,.]+");
  }

  private void parseTransactions(List<TransactionDTO> out, List<Cell> cells) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = toRows(cells);
    if (rows.isEmpty()) {
      return;
    }

    Map<String, Integer> columns = new LinkedHashMap<>(DEFAULT_COLUMNS);
    int firstDataRow = rows.firstKey();
    TreeMap<Integer, String> firstRow = rows.firstEntry().getValue();
    Map<Integer, String> detected = new LinkedHashMap<>();
    for (Map.Entry<Integer, String> e : firstRow.entrySet()) {
      String field = HEADER_COLUMN_NAMES.get(e.getValue().trim());
      if (field != null) {
        detected.put(e.getKey(), field);
      }
    }
    if (detected.size() >= 10) {
      columns.clear();
      detected.forEach((col, field) -> columns.put(field, col));
      firstDataRow++; // 表头行不参与交易
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
      tx.setTransDate(parseDate(value(row, "transDate", columns)));
      if (tx.getTransDate() == null) {
        tx.setTransDate(parseDate(timeCell));
      }
      tx.setTransTime(parseTime(timeCell));
      if (tx.getTransDate() != null && tx.getTransTime() != null) {
        tx.setTransDateTime(LocalDateTime.of(tx.getTransDate(),
          LocalTime.parse(tx.getTransTime())));
      }

      BigDecimal expense = parseAmount(value(row, "expense", columns));
      BigDecimal income = parseAmount(value(row, "income", columns));
      if (expense != null && expense.signum() > 0) {
        tx.setAmount(expense.negate());
        tx.setDirection("出");
      } else if (income != null && income.signum() > 0) {
        tx.setAmount(income);
        tx.setDirection("入");
      }
      tx.setBalance(parseAmount(value(row, "balance", columns)));

      tx.setCounterpartyAccount(normalizeAccount(
        value(row, "counterpartyAccount", columns)));
      tx.setCounterpartyName(joinText(value(row, "counterpartyName", columns)));
      tx.setPurpose(joinText(value(row, "purpose", columns)));
      tx.setPostscript(joinText(value(row, "postscript", columns)));
      tx.setSummary(joinText(value(row, "summary", columns)));
      tx.setRemark(buildRemark(
        value(row, "counterpartyBank", columns),
        value(row, "serialNo", columns),
        value(row, "routeNo", columns)));

      out.add(tx);
    }
  }

  private String buildRemark(String bank, String serialNo, String routeNo) {
    List<String> parts = new ArrayList<>();
    String b = joinText(bank);
    String s = joinText(serialNo);
    String r = joinText(routeNo);
    if (b != null) parts.add("对方行名:" + b);
    if (s != null) parts.add("流水号:" + s);
    if (r != null) parts.add("路由号:" + r);
    return parts.isEmpty() ? null : String.join("；", parts);
  }

  // ---------- 字段清洗 ----------

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
    // 交易时间单元格是"日期+时间"连写，OCR 折行可能把小时的高位拆到日期后：
    // 2025-10-171\n5:56:05 实际是 15:56:05
    String flat = s.replaceAll("\\s+", "");
    Matcher dateMatcher = YMD_PATTERN.matcher(flat);
    if (dateMatcher.lookingAt()) {
      String rest = flat.substring(dateMatcher.end());
      Matcher timeMatcher = TIME_PATTERN.matcher(rest);
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
    if (cleaned.isEmpty()) {
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

  /** 单元格内换行是 OCR 折行，不是真实分隔符 */
  private String joinText(String s) {
    if (s == null) {
      return null;
    }
    String cleaned = s.replaceAll("\\s+", "").trim();
    return cleaned.isEmpty() ? null : cleaned;
  }

  /** 对方账号只为数字或掩码；盖章文字（如"业务专用章"）直接丢弃 */
  private String normalizeAccount(String s) {
    String cleaned = joinText(s);
    if (cleaned == null || !cleaned.matches("[0-9*]{6,}")) {
      return null;
    }
    return cleaned;
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
