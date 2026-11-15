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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 桂林国民村镇银行「本行账户明细下载」表格型 OCR（tables_result）解析器。
 *
 * <p>每页一张表，8 列：交易时间 / 收入 / 支出 / 余额 / 对方账户 /
 * 对方户名 / 摘要 / 流水号。没有独立的借贷标志列，靠收入、支出两列判断方向。
 */
public class GlgmczyhTableStatementParser implements BankStatementParserStrategy {

  private static final Pattern DATE_PATTERN =
    Pattern.compile("(\\d{4})[-年]?(\\d{1,2})[-月]?(\\d{1,2})日?");
  private static final Pattern YMD_PATTERN =
    Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
  private static final Pattern TIME_PATTERN =
    Pattern.compile("(\\d{1,2}):(\\d{2}):(\\d{2})");

  private static final int COL_DATE = 0;
  private static final int COL_INCOME = 1;
  private static final int COL_EXPENSE = 2;
  private static final int COL_BALANCE = 3;
  private static final int COL_COUNTERPARTY_ACCOUNT = 4;
  private static final int COL_COUNTERPARTY_NAME = 5;
  private static final int COL_SUMMARY = 6;
  private static final int COL_SERIAL_NO = 7;

  @Override
  public String bankCode() {
    return "GLGMCZYH";
  }

  @Override
  public int priority() {
    return 10;
  }

  @Override
  public boolean supports(ParseContext context) {
    if ("GLGMCZYH".equalsIgnoreCase(context.getExplicitBankCode())) {
      return true;
    }
    List<String> words = context.getAllWords();
    boolean tableHeader = words.stream().anyMatch(w -> w.contains("对方账户"))
      && words.stream().anyMatch(w -> w.contains("交易时间"))
      && words.stream().anyMatch(w -> w.contains("流水号"));
    String fileName = context.getFileName();
    return tableHeader && fileName != null && fileName.contains("本行账户明细");
  }

  @Override
  public BankStatementDTO parse(JSONArray pages) {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    header.setBankName("桂林国民村镇银行");
    dto.setHeader(header);

    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();
    int seqNo = 0;

    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.getJSONObject(p);
      JSONArray tables = page.optJSONArray("tables_result");
      if (tables == null) {
        continue;
      }
      for (int t = 0; t < tables.length(); t++) {
        JSONObject table = tables.getJSONObject(t);
        fillHeader(header, table.optJSONArray("header"));
        List<Cell> cells = collectBodyCells(table);
        appendRaw(raw, table, cells);
        seqNo = parseTransactions(transactions, cells, seqNo);
      }
    }

    StatementSummary summary = buildSummary(transactions);
    dto.setTransactions(transactions);
    dto.setSummary(summary);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  // ---------- 表头 ----------

  private void fillHeader(StatementHeader header, JSONArray words) {
    if (words == null) {
      return;
    }
    for (int i = 0; i < words.length(); i++) {
      String text = words.getJSONObject(i).optString("words", "").trim();
      if (text.startsWith("户名") && text.length() > 2) {
        String value = text.substring(text.indexOf("户名") + 2).replaceAll("^[：:]+", "");
        header.setAccountHolder(joinText(value));
      } else if (text.contains("人民币")) {
        header.setCurrency("人民币");
      }
    }
  }

  private StatementSummary buildSummary(List<TransactionDTO> transactions) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expense = BigDecimal.ZERO;
    int incomeCount = 0;
    int expenseCount = 0;
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
    StatementSummary summary = new StatementSummary();
    summary.setTotalIncome(income);
    summary.setIncomeCount(incomeCount);
    summary.setTotalExpense(expense);
    summary.setExpenseCount(expenseCount);
    return summary;
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

  private int parseTransactions(List<TransactionDTO> out, List<Cell> cells, int seqNo) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();
    for (Cell c : cells) {
      rows.computeIfAbsent(c.row, k -> new TreeMap<>()).put(c.col, c.words);
    }

    for (TreeMap<Integer, String> row : rows.values()) {
      String dateCell = row.get(COL_DATE);
      LocalDate transDate = parseDate(dateCell);
      if (transDate == null) {
        continue; // 表头行/空行
      }

      BigDecimal income = parseAmount(row.get(COL_INCOME));
      BigDecimal expense = parseAmount(row.get(COL_EXPENSE));
      BigDecimal amount;
      String direction;
      if (income != null && income.signum() != 0) {
        amount = income;
        direction = "入";
      } else if (expense != null && expense.signum() != 0) {
        amount = expense.negate();
        direction = "出";
      } else {
        continue;
      }

      TransactionDTO tx = new TransactionDTO();
      tx.setSeqNo(++seqNo);
      tx.setTransDate(transDate);
      String time = parseTime(dateCell);
      tx.setTransTime(time);
      if (time != null) {
        tx.setTransDateTime(LocalDateTime.of(transDate, LocalTime.parse(time)));
      }
      tx.setAmount(amount);
      tx.setBalance(parseAmount(row.get(COL_BALANCE)));
      tx.setDirection(direction);
      tx.setAmountSignExplicit(false);
      tx.setCounterpartyAccount(normalizeAccount(row.get(COL_COUNTERPARTY_ACCOUNT)));
      tx.setCounterpartyName(joinText(row.get(COL_COUNTERPARTY_NAME)));
      tx.setSummary(joinText(row.get(COL_SUMMARY)));
      String serialNo = joinText(row.get(COL_SERIAL_NO));
      tx.setRemark(serialNo == null ? null : "流水号:" + serialNo);
      out.add(tx);
    }
    return seqNo;
  }

  // ---------- 字段清洗 ----------

  private LocalDate parseDate(String s) {
    if (s == null) {
      return null;
    }
    Matcher m = DATE_PATTERN.matcher(s);
    if (!m.find()) {
      return null;
    }
    return LocalDate.of(Integer.parseInt(m.group(1)),
      Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
  }

  private String parseTime(String s) {
    if (s == null) {
      return null;
    }
    Matcher dateMatcher = YMD_PATTERN.matcher(s.replaceAll("\\s+", ""));
    if (!dateMatcher.find()) {
      return null;
    }
    Matcher timeMatcher = TIME_PATTERN.matcher(s.substring(dateMatcher.end()));
    if (!timeMatcher.find()) {
      return null;
    }
    return String.format("%02d:%02d:%02d",
      Integer.parseInt(timeMatcher.group(1)),
      Integer.parseInt(timeMatcher.group(2)),
      Integer.parseInt(timeMatcher.group(3)));
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

  /** 单元格内换行是 OCR 折行，不是真实分隔符 */
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
