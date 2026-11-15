package tech.wetech.admin3.ocrApi.strategy;

import org.json.JSONArray;
import org.json.JSONObject;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ConfigurableStatementParserCore {

  private static final Pattern TIME_PATTERN = Pattern.compile("\\d{2}:[\\dT]{2}:[\\dT]{2}");
  private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private final StatementLayout layout;

  public ConfigurableStatementParserCore(StatementLayout layout) {
    this.layout = layout;
  }

  public BankStatementDTO parse(JSONArray pages) {
    BankStatementDTO result = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    header.setBankName(layout.getBankNameKeyword());   // 直接来自 Layout
    result.setHeader(header);
    List<TransactionDTO> allTx = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.getJSONObject(p);
      if (!page.has("words_result")) {
        continue;   // OCR 限流空页，跳过
      }
      JSONArray wordsArr = page.getJSONArray("words_result");
      List<String> words = new ArrayList<>();
      for (int i = 0; i < wordsArr.length(); i++) {
        words.add(wordsArr.getJSONObject(i).getString("words"));
      }
      raw.append(String.join("|", words)).append("\n");

      if (p == 0) fillHeader(result.getHeader(), words);
      if (p == pages.length() - 1) result.setSummary(parseSummary(words));
      allTx.addAll(parseTransactions(words));
    }
    // ★所有页解析完，allTx 已收集齐
    validateAndFixByBalance(allTx);

    result.setTransactions(allTx);
    result.setRawOcrText(raw.toString());
    return result;
  }

  private void validateAndFixByBalance(List<TransactionDTO> txs) {
    for (int i = 1; i < txs.size(); i++) {
      TransactionDTO prev = txs.get(i - 1);
      TransactionDTO curr = txs.get(i);
      if (prev.getBalance() == null || curr.getBalance() == null || curr.getAmount() == null) continue;

      BigDecimal diff = curr.getBalance().subtract(prev.getBalance());
      if (diff.signum() == 0 || curr.getAmount().signum() == 0) continue;

      // 余额变化方向与金额符号不一致 → 以余额为准修正
      if (diff.signum() != curr.getAmount().signum()) {
        curr.setAmount(diff);
        curr.setDirection(diff.signum() < 0 ? "出" : "入");
      }
    }
  }

  // ---------- 表头 ----------
  private void fillHeader(StatementHeader header, List<String> words) {
    for (int i = 0; i < words.size(); i++) {
      String w = words.get(i);
      // 账号：独立 token "账号" → 取下一个 token；"账号：xxx" 粘一起 → 截标签
      if ("账号".equals(w) && i + 1 < words.size()) {
        header.setAccountNo(words.get(i + 1).trim());
        continue;
      }
      if (w.startsWith("账号") && w.length() > 2 && header.getAccountNo() == null) {
        header.setAccountNo(stripInlineLabel(w, "账号"));
        continue;
      }
      // 户名：同上两种形态
      if ("户名".equals(w) && i + 1 < words.size()) {
        header.setAccountHolder(words.get(i + 1).trim());
        continue;
      }
      if (w.startsWith("户名") && w.length() > 2 && header.getAccountHolder() == null) {
        header.setAccountHolder(stripInlineLabel(w, "户名"));
        continue;
      }
      // 卡号/账号/户名 组合标签
      if (w.contains("户名") && w.matches(".*[/／].*")) {
        // 形如 "账号/户名：xxx xxx"，让 layout 下面去处理
      }
      if ("起止日期".equals(w) && i + 1 < words.size()) {
        String range = words.get(i + 1);
        String[] parts = range.split("[-~至]");
        if (parts.length == 2) {
          header.setStartDate(parseLooseDate(parts[0]));
          header.setEndDate(parseLooseDate(parts[1]));
        }
        continue;
      }
      for (Map.Entry<String, String> e : layout.getHeaderLabels().entrySet()) {
        String label = e.getKey();
        if (w.startsWith(label)) {
          applyHeaderField(header, e.getValue(), w.substring(label.length()).trim());
          break;
        }
      }
    }
  }

  /** OCR 把标签和值粘成一个 token（如 "户名：广西达远科技有限公司"）时，剥掉前缀标签和冒号/空白 */
  private static String stripInlineLabel(String word, String label) {
    return word.substring(label.length()).replaceAll("^[：:／/]+\\s*", "").trim();
  }

  private LocalDate parseLooseDate(String s) {
    // 2025年09月01日 → 2025-09-01
    String clean = s.replace("年", "-").replace("月", "-").replace("日", "").trim();
    // 去掉多余的前导 0
    String[] parts = clean.split("-");
    if (parts.length == 3) {
      return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }
    return null;
  }

  private void applyHeaderField(StatementHeader header, String field, String value) {
    switch (field) {
      case "accountNo" -> header.setAccountNo(value);
      case "accountHolder" -> header.setAccountHolder(value);
      case "dateRange" -> {
        String[] parts = value.split("至");
        if (parts.length == 2) {
          header.setStartDate(LocalDate.parse(parts[0].trim()));
          header.setEndDate(LocalDate.parse(parts[1].trim()));
        }
      }
      case "bankName" -> header.setBankName(value);
      default -> {
      }
    }
  }

  // ---------- 交易 ----------
  private List<TransactionDTO> parseTransactions(List<String> words) {
    List<TransactionDTO> list = new ArrayList<>();
    TransactionRowDetector detector = layout.getRowDetector();
    int i = 0;
    while (i < words.size()) {
      if (detector.isRowStart(words, i)) {
        int end = detector.findNextRow(words, i + 1);
        if (end <= i) end = i + 1;          // 防御：严格递增，防死循环
        TransactionDTO tx = parseOneTransaction(words, i, end);
        if (tx != null) list.add(tx);
        i = end;                             // 用同一个 end 推进
      } else {
        i++;
      }
    }
    return list;
  }

  private TransactionDTO parseOneTransaction(List<String> words, int start, int end) {
    TransactionDTO tx = new TransactionDTO();
    int dateIndex;
    if (DATE_PATTERN.matcher(words.get(start)).matches()) {
      dateIndex = start;
    } else {
      try {
        tx.setSeqNo(Integer.parseInt(words.get(start)));
      } catch (NumberFormatException e) {
        // 忽略
      }
      dateIndex = start + 1;
    }
    tx.setTransDate(LocalDate.parse(words.get(dateIndex)));

    int segmentStart = dateIndex + 1;
    if (segmentStart >= end) {
      // 只有序号+日期，没有交易内容（异常情况，直接返回）
      return tx;
    }
    List<String> segment = words.subList(segmentStart, end);

    Map<String, Integer> hitCount = new HashMap<>();
    List<String> remaining = new ArrayList<>();

    for (String w : segment) {
      if (TIME_PATTERN.matcher(w).matches()) {
        tx.setTransTime(w);
        continue;
      }
      // 方向已取到，重复的"出/入"直接跳过，不进 remaining
      if (("出".equals(w) || "入".equals(w)) && tx.getDirection() != null) {
        continue;
      }
      boolean matched = false;
      for (ColumnRule rule : layout.getColumns()) {
        if (rule.matches(w)) {
          if ("amount".equals(rule.getFieldName())) {
            rule.getSetter().accept(tx, w);   // 调 setter，由 setter 决定填 amount 还是 balance
          } else {
            int count = hitCount.merge(rule.getFieldName(), 1, Integer::sum);
            if (count == rule.getOccurrence()) {
              rule.getSetter().accept(tx, w);
            }
          }
          matched = true;
          break;
        }
      }
      if (!matched) remaining.add(w);
    }

    fillOrderedTextFields(tx, remaining);

    if (tx.getTransDate() != null && tx.getTransTime() != null) {
      tx.setTransDateTime(LocalDateTime.of(tx.getTransDate(), LocalTime.parse(tx.getTransTime())));
    }
    if (!tx.getAmountSignExplicit() && "出".equals(tx.getOcrDirection()) && tx.getAmount().signum() > 0) {
      tx.setAmount(tx.getAmount().negate());
    }
    String dir = resolveDirection(tx);
    tx.setDirection(dir);
    if ("出".equals(dir) && tx.getAmount() != null && tx.getAmount().signum() > 0 && !tx.getAmountSignExplicit()) {
      tx.setAmount(tx.getAmount().negate());
    }
    return tx;
  }

  private String resolveDirection(TransactionDTO tx) {
    BigDecimal amount = tx.getAmount();
    String ocr = tx.getOcrDirection();
    boolean hasAmount = amount != null && amount.signum() != 0;

    // 1. 两者都有且一致
    if (hasAmount && ocr != null) {
      String byAmount = amount.signum() < 0 ? "出" : "入";
      if (byAmount.equals(ocr)) return ocr;
      // 2. 矛盾：看关键词
      if (containsAny(tx.getPurpose(), layout.getExpenseKeywords()) || containsAny(tx.getSummary(), layout.getExpenseKeywords()))
        return "出";
      if (containsAny(tx.getPurpose(), layout.getIncomeKeywords()) || containsAny(tx.getSummary(), layout.getIncomeKeywords()))
        return "入";
      // 关键词也判不了，信 OCR 原词（银行打印的方向）
      return ocr;
    }

    // 3. 只有金额
    if (hasAmount) {
      if (amount.signum() < 0) return "出";
      if (containsAny(tx.getPurpose(), layout.getExpenseKeywords()) || containsAny(tx.getSummary(), layout.getExpenseKeywords()))
        return "出";
      return "入";
    }

    // 4. 只有 OCR 词
    return ocr;
  }

  private boolean containsAny(String s, List<String> keywords) {
    if (s == null || keywords == null) return false;
    return keywords.stream().anyMatch(s::contains);
  }

  private void fillOrderedTextFields(TransactionDTO tx, List<String> texts) {
    List<Pattern> ignore = layout.getIgnorePatterns();
    if (ignore != null && !ignore.isEmpty()) {
      texts.removeIf(t -> ignore.stream().anyMatch(p -> p.matcher(t).matches()));
    }

    OrderedTextRule rule = layout.getOrderedTextRule();
    if (rule == null) return;

    int fieldIdx = 0;
    for (String text : texts) {
      // 1. 公司后缀拼接（不管 fieldIdx 是否已满）
      if (isCompanySuffix(text) && tx.getCounterpartyName() != null && !tx.getCounterpartyName().endsWith("公司") && !tx.getCounterpartyName().endsWith("限公司") && !tx.getCounterpartyName().endsWith("有限公司")) {
        tx.setCounterpartyName(tx.getCounterpartyName() + text);
        continue;
      }
      // 2. "司"/"可" 续接（仅当户名以"有限公"结尾）
      if (("司".equals(text) || "可".equals(text)) && tx.getCounterpartyName() != null && tx.getCounterpartyName().endsWith("有限公")) {
        tx.setCounterpartyName(tx.getCounterpartyName() + text);
        continue;
      }
      // 3. 普通字段填充
      if (fieldIdx >= rule.getSetters().size()) break;
      rule.getSetters().get(fieldIdx).accept(tx, text);
      fieldIdx++;
    }
  }

  private boolean isCompanySuffix(String s) {
    if (s == null) return false;
    return s.equals("公司") || s.equals("限公司") || s.equals("有限") || s.endsWith("有限公司") || s.endsWith("有限公可") || s.endsWith("有限公可司") || s.endsWith("公可") || s.endsWith("公可司");
  }

  // ---------- 汇总 ----------
  private StatementSummary parseSummary(List<String> words) {
    StatementSummary s = new StatementSummary();
    for (String w : words) {
      for (Map.Entry<String, String> e : layout.getSummaryLabels().entrySet()) {
        String label = e.getKey();
        if (w.startsWith(label)) {
          applySummaryField(s, e.getValue(), w.substring(label.length()).trim());
          break;
        }
      }
    }
    return s;
  }

  private void applySummaryField(StatementSummary s, String field, String value) {
    switch (field) {
      case "totalExpense" -> s.setTotalExpense(AmountNormalizer.normalize(value));
      case "expenseCount" -> s.setExpenseCount(Integer.parseInt(value));
      case "totalIncome" -> s.setTotalIncome(AmountNormalizer.normalize(value));
      case "incomeCount" -> s.setIncomeCount(Integer.parseInt(value));
      case "applyTime" -> s.setApplyTime(value);
      default -> {
      }
    }
  }
}
