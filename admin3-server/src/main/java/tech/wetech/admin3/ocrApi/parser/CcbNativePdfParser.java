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
import java.util.ArrayList;
import java.util.List;
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

  /** 完整交易行（带序号）；部分行（如「利息收入」）没有 *** 分隔符，故 *** 设为可选 */
  private static final Pattern FULL_TX = Pattern.compile(
    "^(\\d+)\\s+(.+?)\\s+(\\d{8})\\s+(-?[\\d,.]+)\\s+(-?[\\d,.]+)(?:\\s+\\*\\*\\*)?\\s*(.*)$");

  /** 完整数据行（不带序号，用于摘要续行场景） */
  private static final Pattern DATA_ONLY = Pattern.compile(
    "^(\\d{8})\\s+(-?[\\d,.]+)\\s+(-?[\\d,.]+)\\s+\\*\\*\\*\\s*(.*)$");

  /** 数据行但在行首粘了摘要续字，如「款 20251023 1,914.01 43,417.62 *** ...」 */
  private static final Pattern FRAG_DATA = Pattern.compile(
    "^([^\\d\\s]+)\\s+(\\d{8})\\s+(-?[\\d,.]+)\\s+(-?[\\d,.]+)\\s+\\*\\*\\*\\s*(.*)$");

  /** 序号 + 摘要（可能只含摘要的前半段） */
  private static final Pattern SEQ_SUMMARY = Pattern.compile("^(\\d+)\\s+(.*)$");

  /** 分页行 */
  private static final Pattern PAGE_SEP = Pattern.compile("-\\s*第\\d+页/共\\d+页\\s*-");

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
      List<String> lines = new ArrayList<>();
      for (int p = 1; p <= doc.getNumberOfPages(); p++) {
        s.setStartPage(p); s.setEndPage(p);
        String pageText = s.getText(doc);
        raw.append(pageText).append('\n');
        if (p == 1) fillHeader(pageText, header, summary);

        for (String line : pageText.split("\\r?\\n")) {
          String t = line.trim();
          if (t.isEmpty()) continue;
          if (PAGE_SEP.matcher(t).find()) continue;
          if (t.contains("个人活期账户全部交易明细")) continue;
          if (t.startsWith("卡号/账号") || t.startsWith("卡号／账号")) continue;
          if (t.startsWith("当前时间段收支金额合计")) continue;
          if (t.startsWith("序号") && t.contains("摘要") && t.contains("交易日期")) continue;
          if (t.startsWith("生成时间") || t.startsWith("温馨提示")) continue;
          lines.add(t);
        }
      }

      // 解析
      int lastSeq = 0;
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        Matcher full = FULL_TX.matcher(line);
        Matcher dataOnly = DATA_ONLY.matcher(line);
        Matcher fragData = FRAG_DATA.matcher(line);

        Integer seqNo = null;
        String summaryText = null;
        String dateStr, amtStr, balStr, rest;

        if (full.find()) {
          // 完整行，带序号
          seqNo = Integer.parseInt(full.group(1));
          summaryText = full.group(2).trim();
          dateStr = full.group(3); amtStr = full.group(4); balStr = full.group(5);
          rest = full.group(6) == null ? "" : full.group(6).trim();
        } else if (dataOnly.find()) {
          // 不带序号——向前看 1-3 行收集序号和摘要
          dateStr = dataOnly.group(1); amtStr = dataOnly.group(2);
          balStr = dataOnly.group(3);
          rest = dataOnly.group(4) == null ? "" : dataOnly.group(4).trim();

          // 向前收集
          StringBuilder sb = new StringBuilder();
          for (int j = Math.max(0, i - 3); j < i; j++) {
            String prev = lines.get(j);
            if (DATA_ONLY.matcher(prev).find()) break;  // 另一个数据行
            Matcher pm = FULL_TX.matcher(prev);
            if (pm.find()) break;
            // 检查上一页的完整行
            if (PAGE_SEP.matcher(prev).find()) continue;
            if (prev.isEmpty()) continue;
            // 如果这行以数字开头，可能是序号+摘要
            Matcher seqm = Pattern.compile("^(\\d+)\\s+(.*)$").matcher(prev);
            if (seqm.find()) {
              if (seqNo == null) {
                try { seqNo = Integer.parseInt(seqm.group(1)); } catch (Exception ignored) {}
              }
              String frag = seqm.group(2).trim();
              if (!frag.isEmpty()) {
                if (sb.length() > 0) sb.insert(0, ' ');
                sb.insert(0, frag);
              }
            } else {
              // 可能是摘要续字
              if (!prev.isEmpty()) {
                if (sb.length() > 0) sb.insert(0, ' ');
                sb.insert(0, prev);
              }
            }
          }
          summaryText = sb.toString().trim();
        } else if (fragData.find()) {
          // 数据行行首粘了摘要续字，如「款 20251023 1,914.01 43,417.62 *** ...」
          // 上一行是「1 人民币卡消费」，拼起来才是完整摘要「人民币卡消费款」
          dateStr = fragData.group(2); amtStr = fragData.group(3);
          balStr = fragData.group(4);
          rest = fragData.group(5) == null ? "" : fragData.group(5).trim();

          StringBuilder sb = new StringBuilder();
          for (int j = i - 1; j >= 0 && j >= i - 3; j--) {
            String prev = lines.get(j);
            if (FULL_TX.matcher(prev).find() || DATA_ONLY.matcher(prev).find()
              || FRAG_DATA.matcher(prev).find()) {
              break;
            }
            Matcher sm = SEQ_SUMMARY.matcher(prev);
            if (sm.find()) {
              try { seqNo = Integer.parseInt(sm.group(1)); } catch (Exception ignored) {}
              sb.insert(0, sm.group(2).trim());
              break;
            }
          }
          summaryText = sb.toString().trim() + fragData.group(1).trim();
        } else {
          continue;  // 不是交易行
        }

        TransactionDTO tx = new TransactionDTO();
        if (seqNo != null) {
          tx.setSeqNo(seqNo);
          lastSeq = seqNo;
        } else {
          lastSeq++;
          tx.setSeqNo(lastSeq);
        }

        if (summaryText != null && !summaryText.isEmpty()) {
          tx.setSummary(summaryText);
        }

        LocalDate d = LocalDate.of(
          Integer.parseInt(dateStr.substring(0, 4)),
          Integer.parseInt(dateStr.substring(4, 6)),
          Integer.parseInt(dateStr.substring(6, 8)));
        tx.setTransDate(d);

        BigDecimal amount = AmountNormalizer.normalize(amtStr);
        BigDecimal balance = AmountNormalizer.normalize(balStr);
        tx.setAmount(amount);
        tx.setBalance(balance);
        tx.setDirection(amount != null && amount.signum() >= 0 ? "入" : "出");
        tx.setAmountSignExplicit(true);

        fillCounterparty(tx, rest);
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

  private static void fillCounterparty(TransactionDTO tx, String rest) {
    if (rest == null || rest.isEmpty()) return;
    // rest = 附言  对方账号/对方户名
    Matcher m = Pattern.compile("(\\d{6,})\\s*/\\s*(.+?)$").matcher(rest);
    if (m.find()) {
      tx.setCounterpartyAccount(m.group(1));
      String name = m.group(2).trim();
      if (name.length() > 100) name = name.substring(0, 100);
      tx.setCounterpartyName(name.isEmpty() ? null : name);
      String post = rest.substring(0, m.start()).trim();
      if (post.length() > 200) post = post.substring(0, 200);
      tx.setPostscript(post.isEmpty() ? null : post);
    } else {
      if (rest.length() > 200) rest = rest.substring(0, 200);
      tx.setPostscript(rest);
    }
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
