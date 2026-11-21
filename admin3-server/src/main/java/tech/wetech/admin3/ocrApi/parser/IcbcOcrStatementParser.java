package tech.wetech.admin3.ocrApi.parser;

import org.json.JSONArray;
import org.json.JSONObject;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国工商银行借记账户历史明细（扫描件）解析器。
 *
 * <p>该 PDF 无文本层，需先由百度 OCR 表格接口按页识别，得到 pages JSON：
 * 每页 {@code tables_result[0]} 包含 header（标题/起止日期）、body（单元格矩阵）、footer（本页合计）。
 *
 * <p>表头列（11 列）：
 * <pre>
 * 交易日期 | 账号 | 储种 | 序号 | 币种 | 钞汇 | 摘要 | 地区 | 收入/支出金额 | 余额 | 渠道
 * </pre>
 *
 * <p>金额特殊点：OCR 会把千分位逗号识别成点号，如 {@code -10.000.00} 实为 -10000.00，
 * 因此解析时把最后一个点/逗号当小数点，其余当千分位剔除。
 */
public final class IcbcOcrStatementParser {

  private static final Pattern P_RANGE = Pattern.compile(
    "(\\d{4}-\\d{2}-\\d{2})\\s*[-~至]\\s*(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern P_DATE = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
  private static final Pattern P_TIME = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})$");
  private static final Pattern P_HOLDER = Pattern.compile("户名\\s*[：:]\\s*([^\\s：:]+)");
  /** 页脚合计（兼容千分位点号：67.757.51） */
  private static final Pattern P_FOOTER_INCOME = Pattern.compile("本页收入算术合计[：:]*([\\d.,]+)");
  private static final Pattern P_FOOTER_EXPENSE = Pattern.compile("本页支出算术合计[：:]*([\\d.,]+)");
  private static final Pattern P_FOOTER_COUNT = Pattern.compile("本页交易笔数[：:]*([\\d]+)");
  private static final BigDecimal CENT = new BigDecimal("0.01");

  private IcbcOcrStatementParser() {}

  public static BankStatementDTO parse(JSONArray pages) {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    header.setBankName("中国工商银行");
    header.setCurrency("人民币");
    readGeneralHeader(pages, header);

    Map<Integer, String> columnMap = defaultColumnMap();
    String accountNo = null;
    LocalDate earliest = null;
    LocalDate latest = null;
    BigDecimal carryBalance = null;

    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.optJSONObject(p);
      if (page == null) continue;
      JSONArray tables = page.optJSONArray("tables_result");
      if (tables == null) continue;

      for (int t = 0; t < tables.length(); t++) {
        JSONObject table = tables.optJSONObject(t);
        if (table == null) continue;

        readHeader(table, header);

        JSONArray body = table.optJSONArray("body");
        if (body == null || body.length() == 0) continue;

        List<TransactionDTO> pageTxs = new ArrayList<>();
        for (List<JSONObject> row : groupRows(body).values()) {
          if (isHeaderRow(row)) {
            columnMap = buildColumnMap(row);
            continue;
          }
          TransactionDTO tx = toTransaction(row, columnMap);
          if (tx == null) continue;

          pageTxs.add(tx);
          raw.append(rowText(row)).append('\n');

          if (tx.getTransDate() != null) {
            if (earliest == null || tx.getTransDate().isBefore(earliest)) earliest = tx.getTransDate();
            if (latest == null || tx.getTransDate().isAfter(latest)) latest = tx.getTransDate();
          }
          if (accountNo == null) {
            accountNo = cell(row, columnMap, "账号");
          }
        }

        // 逐页用「本页收入/支出算术合计」校正金额与余额的解释
        carryBalance = reconcilePage(pageTxs, carryBalance, readFooter(table));
        transactions.addAll(pageTxs);
      }
    }

    for (int i = 0; i < transactions.size(); i++) {
      transactions.get(i).setSeqNo(i + 1);
    }

    header.setAccountNo(accountNo);
    // 页眉起止日期优先；缺失时用首末笔交易日期兜底
    if (header.getStartDate() == null) header.setStartDate(earliest);
    if (header.getEndDate() == null) header.setEndDate(latest);

    fillSummary(transactions, summary);

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  // ==================== 表头 / 行列 ====================

  /**
   * 表格识别接口只保留表格区域，表格上方的"卡号 / 户名"行会丢失。
   * 因此第 1 页额外存了一份通用 OCR 结果（general_words_result），从中取户名。
   */
  private static void readGeneralHeader(JSONArray pages, StatementHeader header) {
    if (pages == null || pages.length() == 0) return;
    JSONObject first = pages.optJSONObject(0);
    if (first == null) return;
    JSONArray lines = first.optJSONArray("general_words_result");
    if (lines == null) return;
    for (int i = 0; i < lines.length(); i++) {
      String line = clean(lines.optString(i, ""));
      Matcher m = P_HOLDER.matcher(line);
      if (m.find()) {
        header.setAccountHolder(m.group(1).trim());
        return;
      }
    }
  }

  private static void readHeader(JSONObject table, StatementHeader header) {
    JSONArray hdr = table.optJSONArray("header");
    if (hdr == null) return;
    for (int i = 0; i < hdr.length(); i++) {
      JSONObject item = hdr.optJSONObject(i);
      if (item == null) continue;
      String words = item.optString("words", "");
      Matcher m = P_RANGE.matcher(words.replace(" ", ""));
      if (m.find()) {
        header.setStartDate(LocalDate.parse(m.group(1)));
        header.setEndDate(LocalDate.parse(m.group(2)));
      }
    }
  }

  private static Map<Integer, List<JSONObject>> groupRows(JSONArray body) {
    Map<Integer, List<JSONObject>> rows = new TreeMap<>();
    for (int i = 0; i < body.length(); i++) {
      JSONObject cell = body.optJSONObject(i);
      if (cell == null) continue;
      int r = cell.optInt("row_start", -1);
      if (r < 0) continue;
      rows.computeIfAbsent(r, k -> new ArrayList<>()).add(cell);
    }
    rows.values().forEach(row -> row.sort(
      Comparator.comparingInt(c -> c.optInt("col_start", 0))));
    return rows;
  }

  private static boolean isHeaderRow(List<JSONObject> row) {
    for (JSONObject cell : row) {
      if (clean(cell.optString("words", "")).contains("交易日期")) return true;
    }
    return false;
  }

  private static Map<Integer, String> buildColumnMap(List<JSONObject> headerRow) {
    Map<Integer, String> map = new HashMap<>();
    for (JSONObject cell : headerRow) {
      int col = cell.optInt("col_start", -1);
      if (col >= 0) map.put(col, clean(cell.optString("words", "")));
    }
    return map;
  }

  private static Map<Integer, String> defaultColumnMap() {
    Map<Integer, String> map = new HashMap<>();
    map.put(0, "交易日期");
    map.put(1, "账号");
    map.put(2, "储种");
    map.put(3, "序号");
    map.put(4, "币种");
    map.put(5, "钞汇");
    map.put(6, "摘要");
    map.put(7, "地区");
    map.put(8, "收入/支出金额");
    map.put(9, "余额");
    map.put(10, "渠道");
    return map;
  }

  /** 按列名关键字取单元格值 */
  private static String cell(List<JSONObject> row, Map<Integer, String> columnMap, String keyword) {
    for (Map.Entry<Integer, String> e : columnMap.entrySet()) {
      if (e.getValue() != null && e.getValue().contains(keyword)) {
        for (JSONObject cell : row) {
          if (cell.optInt("col_start", -1) == e.getKey()) {
            String v = clean(cell.optString("words", ""));
            return v.isEmpty() ? null : v;
          }
        }
      }
    }
    return null;
  }

  // ==================== 交易行 ====================

  private static TransactionDTO toTransaction(List<JSONObject> row, Map<Integer, String> columnMap) {
    String dateCell = cell(row, columnMap, "交易日期");
    if (dateCell == null) return null;

    String[] parts = dateCell.replace('\n', ' ').trim().split("\\s+");
    Matcher dm = P_DATE.matcher(parts[0]);
    if (!dm.matches()) return null;

    LocalDate date = LocalDate.of(
      Integer.parseInt(dm.group(1)),
      Integer.parseInt(dm.group(2)),
      Integer.parseInt(dm.group(3)));

    TransactionDTO tx = new TransactionDTO();
    tx.setTransDate(date);

    if (parts.length > 1) {
      String timeStr = parts[1];
      if (timeStr.length() == 7) timeStr = "0" + timeStr;
      if (P_TIME.matcher(timeStr).matches()) {
        tx.setTransTime(timeStr);
        tx.setTransDateTime(LocalDateTime.of(date, LocalTime.parse(timeStr)));
      }
    }

    BigDecimal amount = parseAmount(cell(row, columnMap, "金额"));
    BigDecimal balance = parseAmount(cell(row, columnMap, "余额"));
    tx.setAmount(amount);
    tx.setBalance(balance);
    if (amount != null) {
      tx.setDirection(amount.signum() < 0 ? "出" : "入");
      tx.setAmountSignExplicit(true);
    }

    tx.setSummary(cell(row, columnMap, "摘要"));
    tx.setChannel(cell(row, columnMap, "渠道"));

    StringBuilder remark = new StringBuilder();
    appendPart(remark, "地区", cell(row, columnMap, "地区"));
    appendPart(remark, "储种", cell(row, columnMap, "储"));
    appendPart(remark, "钞汇", cell(row, columnMap, "钞汇"));
    tx.setRemark(remark.length() == 0 ? null : remark.toString());

    return tx;
  }

  private static void appendPart(StringBuilder sb, String label, String value) {
    if (value == null || value.isEmpty()) return;
    if (sb.length() > 0) sb.append(' ');
    sb.append(label).append(':').append(value);
  }

  // ==================== 金额 / 汇总 ====================

  /**
   * 兼容 OCR 金额格式：{@code -10.000.00}、{@code +4.151.85}、{@code 105,146.87}。
   * 规则：最后一个点/逗号视为小数点，其余分隔符视为千分位剔除。
   *
   * <p>OCR 偶发把笔画识别成单独一行（如余额 {@code 25.898.97} 后面多出 "0"），
   * 因此单元格内按空白拆分，优先取以两位小数结尾的那一段。
   */
  static BigDecimal parseAmount(String raw) {
    if (raw == null) return null;
    String normalized = raw.replace('，', ',').replace('。', '.').trim();
    if (normalized.isEmpty()) return null;

    String s = null;
    for (String token : normalized.split("\\s+")) {
      if (token.matches("[+-]?[\\d.,]+") && token.matches(".*[.,]\\d{2}$")) {
        s = token;
        break;
      }
    }
    if (s == null) {
      for (String token : normalized.split("\\s+")) {
        if (token.matches("[+-]?[\\d.,]+")) {
          s = token;
          break;
        }
      }
    }
    if (s == null || "-".equals(s) || "--".equals(s)) return null;

    String sign = "";
    if (s.startsWith("+")) {
      s = s.substring(1);
    } else if (s.startsWith("-")) {
      sign = "-";
      s = s.substring(1);
    }
    if (s.isEmpty()) return null;

    int lastSep = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
    if (lastSep >= 0 && s.length() - lastSep - 1 <= 2) {
      String intPart = s.substring(0, lastSep).replaceAll("[.,]", "");
      String decPart = s.substring(lastSep + 1);
      if (intPart.isEmpty()) intPart = "0";
      s = intPart + "." + decPart;
    } else {
      s = s.replaceAll("[.,]", "");
    }
    try {
      return new BigDecimal(sign + s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * 逐页用「本页收入/支出算术合计」约束校正金额与余额。
   *
   * <p>正常情况下 {@code prevBalance + amount = balance}。两边对不上时有两种解释：
   * <ul>
   *   <li>金额对、余额丢位（如 7,045.38 被识别成 045.38）；</li>
   *   <li>余额对、金额错位。</li>
   * </ul>
   * 单行无法判断，但页脚有本页收支合计：枚举有歧义行的两种解释，选能让本页合计对上的一种。
   * 歧义行超过 4 个或没有页脚合计时，退回原有启发式（金额/余额数字串前缀相似则修余额，否则修金额）。
   *
   * @param carryIn 上一页最后一笔余额
   * @return 本页最后一笔余额
   */
  private static BigDecimal reconcilePage(List<TransactionDTO> txs, BigDecimal carryIn, PageFooter footer) {
    if (txs.isEmpty()) {
      return carryIn;
    }
    int n = txs.size();
    BigDecimal[] origAmount = new BigDecimal[n];
    BigDecimal[] origBalance = new BigDecimal[n];
    List<Integer> ambiguous = new ArrayList<>();
    List<Boolean> defaultTrustAmount = new ArrayList<>();

    BigDecimal prev = carryIn;
    for (int i = 0; i < n; i++) {
      TransactionDTO tx = txs.get(i);
      origAmount[i] = tx.getAmount();
      origBalance[i] = tx.getBalance();
      if (prev != null && tx.getAmount() != null && tx.getBalance() != null
        && prev.add(tx.getAmount()).subtract(tx.getBalance()).abs().compareTo(CENT) > 0) {
        ambiguous.add(i);
        defaultTrustAmount.add(digitsSimilar(tx.getBalance(), prev.add(tx.getAmount())));
      }
      if (tx.getBalance() != null) {
        prev = tx.getBalance();
      } else if (tx.getAmount() != null && prev != null) {
        prev = prev.add(tx.getAmount());
      }
    }

    BigDecimal end = applyVariants(txs, carryIn, ambiguous, defaultTrustAmount, 0, origAmount, origBalance);
    if (footer == null || ambiguous.isEmpty() || ambiguous.size() > 4 || pageMatches(txs, footer)) {
      return end;
    }
    // 按翻转位数从少到多尝试，找到与页脚合计一致的组合
    int k = ambiguous.size();
    for (int flips = 1; flips <= k; flips++) {
      for (int mask = 0; mask < (1 << k); mask++) {
        if (Integer.bitCount(mask) != flips) {
          continue;
        }
        end = applyVariants(txs, carryIn, ambiguous, defaultTrustAmount, mask, origAmount, origBalance);
        if (pageMatches(txs, footer)) {
          return end;
        }
      }
    }
    // 都匹配不上：恢复默认解释
    return applyVariants(txs, carryIn, ambiguous, defaultTrustAmount, 0, origAmount, origBalance);
  }

  /**
   * 按指定解释组合重算本页：flipMask 的第 i 位表示翻转第 i 个歧义行的默认选择。
   */
  private static BigDecimal applyVariants(List<TransactionDTO> txs, BigDecimal carryIn,
                                          List<Integer> ambiguous, List<Boolean> defaultTrustAmount,
                                          int flipMask, BigDecimal[] origAmount, BigDecimal[] origBalance) {
    BigDecimal prev = carryIn;
    int ambIdx = 0;
    for (int i = 0; i < txs.size(); i++) {
      TransactionDTO tx = txs.get(i);
      BigDecimal amount = origAmount[i];
      BigDecimal balance = origBalance[i];
      boolean isAmbiguous = ambIdx < ambiguous.size() && ambiguous.get(ambIdx) == i;
      if (isAmbiguous) {
        boolean flip = (flipMask & (1 << ambIdx)) != 0;
        boolean trustAmount = defaultTrustAmount.get(ambIdx) ^ flip;
        if (prev != null) {
          if (trustAmount && amount != null) {
            balance = prev.add(amount);
          } else if (balance != null) {
            amount = balance.subtract(prev);
          }
        }
        ambIdx++;
      } else if (prev != null) {
        if (amount != null && balance == null) {
          balance = prev.add(amount);
        } else if (amount == null && balance != null) {
          amount = balance.subtract(prev);
        }
      }
      tx.setAmount(amount);
      tx.setBalance(balance);
      if (amount != null) {
        tx.setDirection(amount.signum() < 0 ? "出" : "入");
        tx.setAmountSignExplicit(true);
      }
      if (balance != null) {
        prev = balance;
      } else if (amount != null && prev != null) {
        prev = prev.add(amount);
      }
    }
    return prev;
  }

  /** 本页解析结果是否与页脚「本页收入/支出算术合计」一致 */
  private static boolean pageMatches(List<TransactionDTO> txs, PageFooter footer) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expense = BigDecimal.ZERO;
    for (TransactionDTO tx : txs) {
      BigDecimal amount = tx.getAmount();
      if (amount == null || amount.signum() == 0) {
        continue;
      }
      if (amount.signum() > 0) {
        income = income.add(amount);
      } else {
        expense = expense.add(amount.negate());
      }
    }
    boolean checked = false;
    if (footer.income != null) {
      checked = true;
      if (income.subtract(footer.income).abs().compareTo(CENT) > 0) {
        return false;
      }
    }
    if (footer.expense != null) {
      checked = true;
      if (expense.subtract(footer.expense).abs().compareTo(CENT) > 0) {
        return false;
      }
    }
    return checked;
  }

  /** 读取页脚合计：本页收入/支出算术合计、本页交易笔数 */
  private static PageFooter readFooter(JSONObject table) {
    JSONArray footer = table.optJSONArray("footer");
    if (footer == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < footer.length(); i++) {
      JSONObject item = footer.optJSONObject(i);
      if (item != null) {
        sb.append(item.optString("words", "")).append(' ');
      }
    }
    String text = sb.toString().replaceAll("[\\s\\u3000]", "");
    BigDecimal income = findAmount(P_FOOTER_INCOME, text);
    BigDecimal expense = findAmount(P_FOOTER_EXPENSE, text);
    Integer count = null;
    Matcher cm = P_FOOTER_COUNT.matcher(text);
    if (cm.find()) {
      try {
        count = Integer.parseInt(cm.group(1));
      } catch (NumberFormatException ignored) {
        // 笔数解析失败不影响金额校验
      }
    }
    if (income == null && expense == null) {
      return null;
    }
    return new PageFooter(income, expense, count);
  }

  private static BigDecimal findAmount(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    return m.find() ? parseAmount(m.group(1)) : null;
  }

  private record PageFooter(BigDecimal income, BigDecimal expense, Integer count) {
  }

  /**
   * 去掉符号和小数点后比较数字串：相等，或前缀关系且位数差 ≤2（即仅小数点位错/丢尾零）。
   * 注意：不做后缀匹配——丢前导位（7045.38 → 045.38）无法只靠本行判断，
   * 交给逐页「本页收支合计」约束来纠错。
   */
  private static boolean digitsSimilar(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) return false;
    String sa = digits(a);
    String sb = digits(b);
    if (sa.equals(sb)) return true;
    return (sa.startsWith(sb) || sb.startsWith(sa))
      && Math.abs(sa.length() - sb.length()) <= 2;
  }

  private static String digits(BigDecimal v) {
    return v.abs().stripTrailingZeros().toPlainString().replace(".", "");
  }

  private static void fillSummary(List<TransactionDTO> txs, StatementSummary summary) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expense = BigDecimal.ZERO;
    int incomeCount = 0;
    int expenseCount = 0;
    for (TransactionDTO tx : txs) {
      BigDecimal amount = tx.getAmount();
      if (amount == null || amount.signum() == 0) continue;
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

  // ==================== 工具 ====================

  private static String clean(String value) {
    return value == null ? "" : value.replace("\n", " ").trim();
  }

  private static String rowText(List<JSONObject> row) {
    StringBuilder sb = new StringBuilder();
    for (JSONObject cell : row) {
      String v = clean(cell.optString("words", ""));
      if (v.isEmpty()) continue;
      if (sb.length() > 0) sb.append('|');
      sb.append(v);
    }
    return sb.toString();
  }
}
