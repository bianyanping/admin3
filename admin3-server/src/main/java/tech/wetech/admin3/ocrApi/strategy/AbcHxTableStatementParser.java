package tech.wetech.admin3.ocrApi.strategy;

import org.json.JSONArray;
import org.json.JSONObject;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.TransactionDTO;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国农业银行「对公交易明细清单」扫描件 / 图片（表格型 OCR）解析器。
 *
 * <p>版式（文档解析 Unlimited-OCR / 表格文字识别V2 转成 tables_result 后的样子）：
 * <pre>
 *   日期 | 摘要附言 | 账簿号 | 交易金额 | 本次余额 | 交易对手账号/对方户名 | 交易行号 | 交易渠道
 *   20260429 | 费用外收 |  | -4.50 | 7826.72 | 20610143CNYFC1644 | 209999 | EBNK
 * </pre>
 *
 * <p>三个关键点：
 * <ul>
 *   <li><b>有符号单金额</b>：本版式只有一个「交易金额」列，负数=支出，正数=收入，不再拆收入/支出两列；</li>
 *   <li><b>长附言串列</b>：该表没有竖线，长附言会压到账簿号/金额/余额/对手列上，OCR 单元格文本会被拼在一起，
 *       因此金额、余额都只取单元格里第一个“两位小数”的数字，其余残字丢弃；</li>
 *   <li><b>余额勾稽</b>：逐笔用「本次余额 − 上一笔余额」校正金额符号/漏识别，跨页也延续。</li>
 * </ul>
 *
 * <p>适配器（DocParserOcrAdapter）已把 cells+matrix 展开成带 row_start/col_start 的 body，
 * 表格 V2 的 body 结构相同，故两种 OCR 提供方共用本解析器。
 */
public class AbcHxTableStatementParser implements BankStatementParserStrategy {

  private static final String F_DATE = "date";
  private static final String F_SUMMARY = "summary";
  private static final String F_POSTSCRIPT = "postscript";
  private static final String F_AMOUNT = "amount";
  private static final String F_INCOME = "income";
  private static final String F_EXPENSE = "expense";
  private static final String F_BALANCE = "balance";
  private static final String F_PARTY = "party";
  private static final String F_PARTY_ACCOUNT = "partyAccount";
  private static final String F_PARTY_NAME = "partyName";
  private static final String F_SERIAL = "serial";
  private static final String F_CHANNEL = "channel";

  /** 交易日期：20260429 / 2026-04-29 / 2026/04/29 */
  private static final Pattern YMD =
    Pattern.compile("^((?:19|20)\\d{2})[-/年.]?(\\d{1,2})[-/月.]?(\\d{1,2})日?$");
  /** 金额：必须带两位小数，避免把日志号、账号当成金额 */
  private static final Pattern MONEY = Pattern.compile("[-+]?\\d[\\d,.]*\\.\\d{2}");
  /** 交易对手账号：字母数字混排（20610143CNYFC1644）或纯数字 */
  private static final Pattern PARTY_ACCOUNT = Pattern.compile("(\\d{6,}[A-Z]{2,}\\d*|\\d{10,})");
  private static final Pattern RANGE = Pattern.compile(
    "((?:19|20)\\d{2})[-/]?(\\d{1,2})[-/]?(\\d{1,2})\\s*[-~至]\\s*"
      + "((?:19|20)\\d{2})[-/]?(\\d{1,2})[-/]?(\\d{1,2})");
  private static final Pattern ACCOUNT_LABEL = Pattern.compile("账号[：:]?\\s*([0-9\\-]{8,})");
  private static final Pattern HOLDER_LABEL =
    Pattern.compile("户名[：:]?\\s*(.+?)(?=\\s|币种|账号|止|$)");
  private static final Pattern CURRENCY_LABEL = Pattern.compile("币种[：:]?\\s*([A-Z]{3})");
  private static final Pattern PAGE_NO = Pattern.compile("第\\s*\\d+\\s*页\\s*[/／]\\s*共\\s*\\d+\\s*页");

  @Override
  public String bankCode() {
    return "ABCHX";
  }

  @Override
  public int priority() {
    return 10;
  }

  @Override
  public boolean supports(ParseContext context) {
    if ("ABCHX".equalsIgnoreCase(context.getExplicitBankCode())) {
      return true;
    }
    boolean abc = context.getAllWords().stream()
      .anyMatch(w -> w.contains("中国农业银行") || w.contains("农业银行"));
    boolean layout = context.getAllWords().stream()
      .anyMatch(w -> w.contains("对公交易明细") || w.contains("本次余额"));
    return abc && layout;
  }

  @Override
  public BankStatementDTO parse(JSONArray pages) {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    header.setBankName("中国农业银行");
    dto.setHeader(header);

    List<TransactionDTO> transactions = new ArrayList<>();
    List<List<BigDecimal>> moneyCandidates = new ArrayList<>();
    StringBuilder raw = new StringBuilder();
    LocalDate earliest = null;
    LocalDate latest = null;

    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.optJSONObject(p);
      if (page == null) {
        continue;
      }
      fillHeader(header, pageWords(page));

      JSONArray tables = page.optJSONArray("tables_result");
      if (tables == null) {
        continue;
      }
      for (int t = 0; t < tables.length(); t++) {
        JSONObject table = tables.optJSONObject(t);
        if (table == null) {
          continue;
        }
        TreeMap<Integer, TreeMap<Integer, String>> rows = toRows(table.optJSONArray("body"));
        if (rows.isEmpty()) {
          continue;
        }
        Map<String, Integer> columns = null;
        for (TreeMap<Integer, String> row : rows.values()) {
          if (isHeaderRow(row)) {
            columns = detectColumns(row);
            break;
          }
        }
        if (columns == null || !columns.containsKey(F_DATE)) {
          columns = defaultColumns(rows.firstEntry().getValue());
        }

        TransactionDTO last = null;
        for (Map.Entry<Integer, TreeMap<Integer, String>> entry : rows.entrySet()) {
          TreeMap<Integer, String> row = entry.getValue();
          if (isHeaderRow(row) || isFooterRow(row)) {
            continue;
          }
          raw.append(row.values()).append('\n');

          String dateText = cell(row, columns, F_DATE);
          LocalDate date = parseDate(dateText);
          if (date == null) {
            // 无日期的行是上一笔的折行：附言 / 对方户名续行
            if (last != null) {
              attachContinuation(last, row, columns);
            }
            continue;
          }

          TransactionDTO tx = new TransactionDTO();
          tx.setTransDate(date);
          tx.setSummary(clean(cell(row, columns, F_SUMMARY)));

          String balanceText = cell(row, columns, F_BALANCE);
          tx.setBalance(money(balanceText));

          List<BigDecimal> candidates = rowCandidates(row, columns);
          BigDecimal prevBalance = last == null ? null : last.getBalance();
          BigDecimal delta = prevBalance != null && tx.getBalance() != null
            ? tx.getBalance().subtract(prevBalance) : null;

          BigDecimal amount = money(cell(row, columns, F_AMOUNT));
          if (amount == null) {
            BigDecimal income = money(cell(row, columns, F_INCOME));
            BigDecimal expense = money(cell(row, columns, F_EXPENSE));
            if (income != null) {
              amount = income;
            } else if (expense != null) {
              amount = expense.negate();
            }
          }
          if (amount == null) {
            // 金额列被长附言挤坏时，用余额差兜底
            amount = matchCandidate(candidates, delta);
          }
          tx.setAmount(amount);

          fillParty(tx, row, columns);
          tx.setChannel(cleanLetters(cell(row, columns, F_CHANNEL)));
          String serial = digits(cell(row, columns, F_SERIAL));
          if (serial != null) {
            tx.setRemark("交易行号:" + serial);
          }

          transactions.add(tx);
          moneyCandidates.add(candidates);
          last = tx;

          if (earliest == null || date.isBefore(earliest)) earliest = date;
          if (latest == null || date.isAfter(latest)) latest = date;
        }
      }
    }

    reconcileShiftedBalance(transactions);
    reconcile(transactions, moneyCandidates);
    removeDuplicateRows(transactions);
    for (int i = 0; i < transactions.size(); i++) {
      transactions.get(i).setSeqNo(i + 1);
    }
    if (header.getStartDate() == null) header.setStartDate(earliest);
    if (header.getEndDate() == null) header.setEndDate(latest);
    if (header.getCurrency() == null) header.setCurrency("人民币");

    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  // ==================== 表头 ====================

  private List<String> pageWords(JSONObject page) {
    List<String> lines = new ArrayList<>();
    JSONArray general = page.optJSONArray("general_words_result");
    if (general != null) {
      for (int i = 0; i < general.length(); i++) {
        lines.add(general.optString(i, ""));
      }
    }
    JSONArray words = page.optJSONArray("words_result");
    if (words != null) {
      for (int i = 0; i < words.length(); i++) {
        JSONObject item = words.optJSONObject(i);
        if (item != null) lines.add(item.optString("words", ""));
      }
    }
    JSONArray tables = page.optJSONArray("tables_result");
    if (tables != null) {
      for (int t = 0; t < tables.length(); t++) {
        JSONObject table = tables.optJSONObject(t);
        JSONArray header = table == null ? null : table.optJSONArray("header");
        if (header == null) {
          continue;
        }
        for (int i = 0; i < header.length(); i++) {
          JSONObject item = header.optJSONObject(i);
          if (item != null) lines.add(item.optString("words", ""));
        }
      }
    }
    return lines;
  }

  private void fillHeader(StatementHeader header, List<String> lines) {
    for (String rawLine : lines) {
      if (rawLine == null) {
        continue;
      }
      String line = rawLine.replaceAll("[\\r\\n\\t]+", " ").trim();
      if (line.isEmpty()) {
        continue;
      }
      if (header.getAccountNo() == null) {
        Matcher m = ACCOUNT_LABEL.matcher(line);
        if (m.find()) {
          header.setAccountNo(m.group(1));
        }
      }
      if (header.getAccountHolder() == null) {
        Matcher m = HOLDER_LABEL.matcher(line);
        if (m.find()) {
          header.setAccountHolder(clean(m.group(1)));
        }
      }
      if (header.getStartDate() == null || header.getEndDate() == null) {
        Matcher m = RANGE.matcher(line);
        if (m.find()) {
          header.setStartDate(LocalDate.of(
            Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
          header.setEndDate(LocalDate.of(
            Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6))));
        }
      }
      if (header.getCurrency() == null) {
        Matcher m = CURRENCY_LABEL.matcher(line);
        if (m.find()) {
          header.setCurrency("CNY".equals(m.group(1)) ? "人民币" : m.group(1));
        }
      }
    }
  }

  // ==================== 行列 ====================

  private TreeMap<Integer, TreeMap<Integer, String>> toRows(JSONArray body) {
    TreeMap<Integer, TreeMap<Integer, String>> rows = new TreeMap<>();
    if (body == null) {
      return rows;
    }
    for (int i = 0; i < body.length(); i++) {
      JSONObject obj = body.optJSONObject(i);
      if (obj == null) {
        continue;
      }
      String words = obj.optString("words", "").trim();
      if (words.isEmpty()) {
        continue;
      }
      int r = obj.optInt("row_start", -1);
      int c = obj.optInt("col_start", -1);
      if (r < 0 || c < 0) {
        continue;
      }
      rows.computeIfAbsent(r, k -> new TreeMap<>()).put(c, words);
    }
    return rows;
  }

  private boolean isHeaderRow(TreeMap<Integer, String> row) {
    boolean date = false;
    boolean money = false;
    for (String v : row.values()) {
      String t = clean(v);
      if (t.contains("日期")) date = true;
      if (t.contains("金额") || t.contains("余额")) money = true;
    }
    return date && money;
  }

  private boolean isFooterRow(TreeMap<Integer, String> row) {
    for (String v : row.values()) {
      String t = clean(v);
      if (PAGE_NO.matcher(t).find() || t.contains("本页") || t.contains("合计")) {
        return true;
      }
    }
    return false;
  }

  private Map<String, Integer> detectColumns(TreeMap<Integer, String> headerRow) {
    Map<String, Integer> columns = new LinkedHashMap<>();
    for (Map.Entry<Integer, String> entry : headerRow.entrySet()) {
      String t = clean(entry.getValue());
      int col = entry.getKey();
      if (t.contains("日期")) {
        columns.put(F_DATE, col);
      } else if (t.contains("摘要")) {
        columns.put(F_SUMMARY, col);
      } else if (t.contains("附言")) {
        columns.put(F_POSTSCRIPT, col);
      } else if (t.contains("余额")) {
        columns.put(F_BALANCE, col);
      } else if (t.contains("收入")) {
        columns.put(F_INCOME, col);
      } else if (t.contains("支出")) {
        columns.put(F_EXPENSE, col);
      } else if (t.contains("金额")) {
        columns.put(F_AMOUNT, col);
      } else if (t.contains("对手") || t.contains("对方")) {
        boolean acct = t.contains("账号");
        boolean name = t.contains("户名");
        if (acct && name) {
          columns.put(F_PARTY, col);
        } else if (acct) {
          columns.put(F_PARTY_ACCOUNT, col);
        } else if (name) {
          columns.put(F_PARTY_NAME, col);
        } else {
          columns.put(F_PARTY, col);
        }
      } else if (t.contains("行号") || t.contains("流水号")) {
        columns.put(F_SERIAL, col);
      } else if (t.contains("渠道")) {
        columns.put(F_CHANNEL, col);
      }
    }
    return columns;
  }

  /** 续页没有表头时按列数猜列：文档解析 8 列（含账簿号），表格V2 7 列（无账簿号） */
  private Map<String, Integer> defaultColumns(TreeMap<Integer, String> firstRow) {
    Map<String, Integer> columns = new LinkedHashMap<>();
    int cols = firstRow.isEmpty() ? 0 : firstRow.lastKey() + 1;
    boolean eight = cols >= 8;
    columns.put(F_DATE, 0);
    columns.put(F_SUMMARY, 1);
    columns.put(F_AMOUNT, eight ? 3 : 2);
    columns.put(F_BALANCE, eight ? 4 : 3);
    columns.put(F_PARTY, eight ? 5 : 4);
    columns.put(F_SERIAL, eight ? 6 : 5);
    columns.put(F_CHANNEL, eight ? 7 : 6);
    return columns;
  }

  private String cell(TreeMap<Integer, String> row, Map<String, Integer> columns, String field) {
    Integer col = columns.get(field);
    if (col == null) {
      return null;
    }
    String value = row.get(col);
    if (value == null) {
      return null;
    }
    return value.replaceAll("[\\r\\n\\t]+", " ").trim();
  }

  // ==================== 交易 ====================

  private void fillParty(TransactionDTO tx, TreeMap<Integer, String> row,
                         Map<String, Integer> columns) {
    String party = cell(row, columns, F_PARTY);
    String account = cell(row, columns, F_PARTY_ACCOUNT);
    String name = clean(cell(row, columns, F_PARTY_NAME));

    if (party != null) {
      String cleaned = party.replaceAll("\\s+", "");
      Matcher m = PARTY_ACCOUNT.matcher(cleaned);
      if (m.find()) {
        if (account == null) {
          account = m.group(1);
        }
        String rest = (cleaned.substring(0, m.start()) + cleaned.substring(m.end())).trim();
        if (!rest.isEmpty()) {
          name = name == null ? rest : rest + name;
        }
      } else if (name == null) {
        name = cleaned;
      }
    }
    tx.setCounterpartyAccount(account == null ? null : account.trim());

    // 长户名会挤到「交易行号」列（如 209999阳支行），把非数字尾巴补回户名
    String serialCell = cell(row, columns, F_SERIAL);
    if (serialCell != null) {
      String tail = serialCell.replaceAll("^[\\d\\s]+", "").trim();
      if (!tail.isEmpty() && tail.length() <= 20) {
        name = name == null ? tail : name + tail;
      }
    }
    tx.setCounterpartyName(name);
  }

  /** 无日期行的内容并入上一笔：附言续行、对方户名续行 */
  private void attachContinuation(TransactionDTO tx, TreeMap<Integer, String> row,
                                  Map<String, Integer> columns) {
    String text = clean(cell(row, columns, F_SUMMARY));
    String postscript = clean(cell(row, columns, F_POSTSCRIPT));
    if (postscript != null && !postscript.equals(text)) {
      text = text == null ? postscript : text + postscript;
    }
    if (text != null && !text.matches("[\\d,.\\s]+")) {
      tx.setPostscript(join(tx.getPostscript(), text));
    }

    String name = clean(cell(row, columns, F_PARTY_NAME));
    if (name == null) {
      String party = cell(row, columns, F_PARTY);
      if (party != null) {
        String cleaned = party.replaceAll("\\s+", "");
        Matcher m = PARTY_ACCOUNT.matcher(cleaned);
        name = clean(m.find()
          ? cleaned.substring(0, m.start()) + cleaned.substring(m.end())
          : cleaned);
      }
    }
    if (name != null && tx.getCounterpartyName() == null) {
      tx.setCounterpartyName(name);
    }
  }

  /** 整行里所有可能的金额，供金额列被附言串坏时兜底 */
  private List<BigDecimal> rowCandidates(TreeMap<Integer, String> row,
                                         Map<String, Integer> columns) {
    List<BigDecimal> list = new ArrayList<>();
    Integer balanceCol = columns.get(F_BALANCE);
    for (Map.Entry<Integer, String> entry : row.entrySet()) {
      if (balanceCol != null && entry.getKey().equals(balanceCol)) {
        continue;
      }
      BigDecimal value = money(entry.getValue());
      if (value != null) {
        list.add(value);
      }
    }
    return list;
  }

  private BigDecimal matchCandidate(List<BigDecimal> candidates, BigDecimal delta) {
    if (candidates.isEmpty()) {
      return delta;
    }
    if (delta != null) {
      for (BigDecimal candidate : candidates) {
        if (candidate.abs().compareTo(delta.abs()) == 0) {
          return delta;
        }
      }
    }
    return candidates.get(0);
  }

  /**
   * 修复「上一笔余额被串成当前笔余额」：整页表格识别时偶发把下一行的余额挂到上一行，
   * 表现为相邻两笔余额完全相同、且当前笔勾稽断裂。
   *
   * <p>用当前笔的金额/余额反推上一笔余额（上一笔余额 = 当前余额 − 当前金额），
   * 并要求再往前一笔的勾稽同时成立才修正；只改余额，绝不反向修改金额。
   */
  private void reconcileShiftedBalance(List<TransactionDTO> transactions) {
    BigDecimal tolerance = new BigDecimal("0.01");
    for (int i = 1; i < transactions.size(); i++) {
      TransactionDTO prev = transactions.get(i - 1);
      TransactionDTO cur = transactions.get(i);
      if (prev.getBalance() == null || cur.getBalance() == null || cur.getAmount() == null) {
        continue;
      }
      boolean consistent = prev.getBalance().add(cur.getAmount())
        .subtract(cur.getBalance()).abs().compareTo(tolerance) <= 0;
      if (consistent || prev.getBalance().compareTo(cur.getBalance()) != 0) {
        continue;
      }
      BigDecimal fixed = cur.getBalance().subtract(cur.getAmount());
      if (i >= 2 && prev.getAmount() != null) {
        TransactionDTO before = transactions.get(i - 2);
        if (before.getBalance() != null
          && before.getBalance().add(prev.getAmount())
          .subtract(fixed).abs().compareTo(tolerance) > 0) {
          // 再往前一笔也对不上，说明不是相邻余额串行，保持原值等待校验告警
          continue;
        }
      }
      prev.setBalance(fixed);
    }
  }

  /**
   * 去掉 OCR 重复识别出的相邻行：表格最后一笔常因折行/边框被识别成两行完全相同的交易，
   * 表现为相邻两笔「日期 + 金额 + 余额」完全一致。
   *
   * <p>同一账户的两笔真实交易不可能在金额相同的情况下余额也相同（余额必然会变），
   * 因此只比较相邻行，避免误删同一天多笔同金额但余额不同的正常交易。
   */
  private void removeDuplicateRows(List<TransactionDTO> transactions) {
    for (int i = transactions.size() - 1; i > 0; i--) {
      TransactionDTO cur = transactions.get(i);
      TransactionDTO prev = transactions.get(i - 1);
      if (sameRow(cur, prev)) {
        transactions.remove(i);
      }
    }
  }

  private boolean sameRow(TransactionDTO a, TransactionDTO b) {
    if (a.getAmount() == null || b.getAmount() == null
      || a.getBalance() == null || b.getBalance() == null) {
      return false;
    }
    String timeA = a.getTransTime() == null ? "" : a.getTransTime();
    String timeB = b.getTransTime() == null ? "" : b.getTransTime();
    return java.util.Objects.equals(a.getTransDate(), b.getTransDate())
      && timeA.equals(timeB)
      && a.getAmount().compareTo(b.getAmount()) == 0
      && a.getBalance().compareTo(b.getBalance()) == 0;
  }

  /**
   * 余额勾稽：金额缺失或符号被 OCR 吃掉时，用「本次余额 − 上一笔余额」修正；
   * 余额缺失时用上一笔余额 + 金额补出。
   */
  private void reconcile(List<TransactionDTO> transactions, List<List<BigDecimal>> candidates) {
    for (int i = 0; i < transactions.size(); i++) {
      TransactionDTO tx = transactions.get(i);
      TransactionDTO prev = i == 0 ? null : transactions.get(i - 1);
      BigDecimal prevBalance = prev == null ? null : prev.getBalance();
      BigDecimal delta = prevBalance != null && tx.getBalance() != null
        ? tx.getBalance().subtract(prevBalance) : null;

      if (tx.getAmount() == null) {
        tx.setAmount(delta);
      } else if (delta != null && tx.getAmount().abs().compareTo(delta.abs()) != 0) {
        BigDecimal matched = matchCandidate(candidates.get(i), delta);
        if (matched != null && matched.abs().compareTo(delta.abs()) == 0) {
          tx.setAmount(matched);
        }
      } else if (delta != null && tx.getAmount().signum() != delta.signum()) {
        tx.setAmount(delta);
      }

      if (tx.getBalance() == null && prevBalance != null && tx.getAmount() != null) {
        tx.setBalance(prevBalance.add(tx.getAmount()));
      }
      if (tx.getAmount() != null) {
        tx.setDirection(tx.getAmount().signum() < 0 ? "出" : "入");
        tx.setAmountSignExplicit(true);
      }
    }
  }

  // ==================== 字段清洗 ====================

  private LocalDate parseDate(String s) {
    if (s == null) {
      return null;
    }
    String flat = s.replaceAll("\\s+", "");
    Matcher m = YMD.matcher(flat);
    if (!m.matches()) {
      return null;
    }
    int month = Integer.parseInt(m.group(2));
    int day = Integer.parseInt(m.group(3));
    if (month < 1 || month > 12 || day < 1 || day > 31) {
      return null;
    }
    return LocalDate.of(Integer.parseInt(m.group(1)), month, day);
  }

  /**
   * 取单元格里第一个两位小数金额；兼容 OCR 把千分位识别成点号（1.234.56）。
   */
  private BigDecimal money(String raw) {
    if (raw == null) {
      return null;
    }
    Matcher m = MONEY.matcher(raw);
    if (!m.find()) {
      return null;
    }
    String token = m.group().replace(",", "");
    int lastDot = token.lastIndexOf('.');
    if (token.indexOf('.') != lastDot) {
      token = token.substring(0, lastDot).replace(".", "") + token.substring(lastDot);
    }
    try {
      return new BigDecimal(token);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String digits(String s) {
    if (s == null) {
      return null;
    }
    String d = s.replaceAll("[^\\d]", "");
    return d.isEmpty() ? null : d;
  }

  private String cleanLetters(String s) {
    if (s == null) {
      return null;
    }
    String v = s.replaceAll("\\s+", "");
    return v.isEmpty() ? null : v;
  }

  private String clean(String s) {
    if (s == null) {
      return null;
    }
    String v = s.replaceAll("\\s+", "").trim();
    return v.isEmpty() ? null : v;
  }

  private String join(String a, String b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return a + b;
  }
}
