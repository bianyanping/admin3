package tech.wetech.admin3.ocrApi.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.AmountNormalizer;
import tech.wetech.admin3.ocrApi.layout.PdfColumnTableReader;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中国建设银行个人活期账户全部交易明细（原生 PDF）解析器。
 *
 * <p>绝大多数交易行以序号开头且一行完整：
 * <pre>
 *   序号 摘要  YYYYMMDD  金额  余额  ***  附言  对方账号/对方户名
 *   2   充值  20251023  -5,709.00  37,708.62  ***  Z******0010/**转账
 * </pre>
 *
 * <p>少数行因 pdfbox 折行，数据行可能不以序号开头（摘要续字在上一行）：
 * <pre>
 *   1 人民币卡消费        ← 序号+摘要（续）
 *   款 20251023 1,914.01 43,417.62 *** ...  ← 续字+完整数据
 * </pre>
 */
public final class CcbNativePdfParser {

  private static final Pattern ACCOUNT = Pattern.compile("卡号[／/]账号[：:]\\s*(\\d+)");
  private static final Pattern HOLDER = Pattern.compile("客户名称[：:]\\s*(\\S+)");
  private static final Pattern DATE_RANGE = Pattern.compile("起止日期[：:]\\s*(\\d{8})-(\\d{8})");
  private static final Pattern CURRENCY = Pattern.compile("币别[：:]\\s*(\\S+)");
  private static final Pattern TOTAL_EXP = Pattern.compile("总支出[：:]\\s*([\\d,.]+)");
  private static final Pattern TOTAL_INC = Pattern.compile("总收入[：:]\\s*([\\d,.]+)");

  private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
  static {
    COLUMNS.put("序号", "seqNo");
    COLUMNS.put("摘要", "summary");
    COLUMNS.put("交易日期", "date");
    COLUMNS.put("交易金额", "amount");
    COLUMNS.put("账户余额", "balance");
    COLUMNS.put("交易地点/附言", "postscript");
    COLUMNS.put("对方账号与户名", "counterparty");
  }
  private static final Set<String> REQUIRED_COLUMNS = Set.of(
    "date", "amount", "balance", "counterparty");
  private static final Pattern ROW_DATE = Pattern.compile("\\d{8}");

  private CcbNativePdfParser() {}

  public static BankStatementDTO parse(File pdfFile) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();
    StringBuilder raw = new StringBuilder();

    header.setBankName("中国建设银行");

    try (PDDocument doc = PDDocument.load(pdfFile)) {
      PDFTextStripper s = new PDFTextStripper();
      s.setSortByPosition(true);

      // 收集所有有效行
      for (int p = 1; p <= doc.getNumberOfPages(); p++) {
        s.setStartPage(p); s.setEndPage(p);
        String pageText = s.getText(doc);
        raw.append(pageText).append('\n');
        if (p == 1) fillHeader(pageText, header, summary);
      }

      // 对方账号/户名会折行（如「浙江爱朴农业科技」+「发展有限公司」），按坐标还原整行
      int lastSeq = 0;
      for (PdfColumnTableReader.Row row : PdfColumnTableReader.read(
        doc, COLUMNS, REQUIRED_COLUMNS, "date", ROW_DATE)) {
        String dateStr = row.get("date");
        Matcher dm = ROW_DATE.matcher(dateStr == null ? "" : dateStr);
        if (!dm.find()) continue;
        String ymd = dm.group();

        Integer seqNo = null;
        String seqText = row.get("seqNo");
        if (seqText != null) {
          try { seqNo = Integer.parseInt(seqText.replaceAll("[^0-9]", "")); } catch (Exception ignored) {}
        }

        TransactionDTO tx = new TransactionDTO();
        if (seqNo != null) {
          tx.setSeqNo(seqNo);
          lastSeq = seqNo;
        } else {
          lastSeq++;
          tx.setSeqNo(lastSeq);
        }

        tx.setSummary(compact(row.get("summary")));
        tx.setTransDate(LocalDate.of(
          Integer.parseInt(ymd.substring(0, 4)),
          Integer.parseInt(ymd.substring(4, 6)),
          Integer.parseInt(ymd.substring(6, 8))));

        BigDecimal amount = amount(row.get("amount"));
        BigDecimal balance = amount(row.get("balance"));
        tx.setAmount(amount);
        tx.setBalance(balance);
        tx.setDirection(amount != null && amount.signum() >= 0 ? "入" : "出");
        tx.setAmountSignExplicit(true);

        tx.setPostscript(compact(row.get("postscript")));
        fillCounterparty(tx, row.get("counterparty"));
        transactions.add(tx);
      }

      // 起止日期兜底
      if (header.getStartDate() == null && !transactions.isEmpty()) {
        header.setStartDate(transactions.get(0).getTransDate());
      }
      if (header.getEndDate() == null && !transactions.isEmpty()) {
        header.setEndDate(transactions.get(transactions.size() - 1).getTransDate());
      }

      recalcIfNeeded(transactions, summary);
    }

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(raw.toString());
    return dto;
  }

  private static void fillHeader(String firstPage, StatementHeader h, StatementSummary s) {
    Matcher m;
    if (h.getAccountNo() == null) {
      m = ACCOUNT.matcher(firstPage);
      if (m.find()) h.setAccountNo(m.group(1));
    }
    if (h.getAccountHolder() == null) {
      m = HOLDER.matcher(firstPage);
      if (m.find()) h.setAccountHolder(m.group(1));
    }
    if (h.getStartDate() == null) {
      m = DATE_RANGE.matcher(firstPage);
      if (m.find()) {
        h.setStartDate(parseYmd(m.group(1)));
        h.setEndDate(parseYmd(m.group(2)));
      }
    }
    if (h.getCurrency() == null) {
      m = CURRENCY.matcher(firstPage);
      if (m.find()) h.setCurrency(m.group(1).replaceAll("元$", ""));
    }
    if (s.getTotalExpense() == null) {
      m = TOTAL_EXP.matcher(firstPage);
      if (m.find()) s.setTotalExpense(AmountNormalizer.normalize(m.group(1)));
    }
    if (s.getTotalIncome() == null) {
      m = TOTAL_INC.matcher(firstPage);
      if (m.find()) s.setTotalIncome(AmountNormalizer.normalize(m.group(1)));
    }
  }

  private static LocalDate parseYmd(String ymd) {
    return LocalDate.of(
      Integer.parseInt(ymd.substring(0, 4)),
      Integer.parseInt(ymd.substring(4, 6)),
      Integer.parseInt(ymd.substring(6, 8)));
  }

  private static void fillCounterparty(TransactionDTO tx, String counterparty) {
    if (counterparty == null || counterparty.isEmpty()) return;
    // 列内容 = 对方账号/对方户名；账号可能是 Z******0010 这类掩码
    Matcher m = Pattern.compile("([0-9A-Za-z*]{4,})\\s*/\\s*(.*)$").matcher(counterparty.trim());
    if (m.find()) {
      tx.setCounterpartyAccount(m.group(1));
      String name = m.group(2).trim();
      if (name.length() > 100) name = name.substring(0, 100);
      tx.setCounterpartyName(name.isEmpty() ? null : name);
    } else if (tx.getPostscript() == null) {
      tx.setPostscript(counterparty.length() > 200 ? counterparty.substring(0, 200) : counterparty);
    }
  }

  private static BigDecimal amount(String text) {
    if (text == null) return null;
    String s = text.replaceAll("[^0-9.,\\-+]", "");
    if (s.isEmpty() || s.equals("-") || s.equals("+") || s.equals(".")) return null;
    try {
      return AmountNormalizer.normalize(s);
    } catch (Exception e) {
      return null;
    }
  }

  private static String compact(String text) {
    if (text == null) return null;
    String value = text.replaceAll("\\s+", " ").trim();
    return value.isEmpty() ? null : value;
  }

  private static void recalcIfNeeded(List<TransactionDTO> txs, StatementSummary s) {
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
