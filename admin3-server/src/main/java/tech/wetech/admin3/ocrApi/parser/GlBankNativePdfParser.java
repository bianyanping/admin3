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
 * 桂林银行「企业客户交易清单」原生 PDF 解析器。
 *
 * <p>版式：单行一笔，列顺序固定为
 * <pre>
 *   交易日期 | 对方账号 | 对方户名 | 收入 | 支出 | 余额
 * </pre>
 * PDFBox 抽取后每笔正好是一行，因此直接按行正则解析；末页带「收入总计 / 支出总计」。
 */
public final class GlBankNativePdfParser {

  private static final Pattern TX_LINE = Pattern.compile(
    "^(\\d{4}-\\d{2}-\\d{2})\\s+([0-9A-Za-z]{5,})\\s+(.+?)\\s+(-?[\\d,]+\\.\\d{2})\\s+(-?[\\d,]+\\.\\d{2})\\s+(-?[\\d,]+\\.\\d{2})$");

  private static final Pattern ACCOUNT = Pattern.compile("账\\s*号\\s*[：:]\\s*(\\d{10,})");
  private static final Pattern HOLDER = Pattern.compile("户\\s*名\\s*[：:]\\s*([\\u4e00-\\u9fa5A-Za-z（）()]+)");
  private static final Pattern START = Pattern.compile("查询起日\\s*[：:]\\s*(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern END = Pattern.compile("查询止日\\s*[：:]\\s*(\\d{4}-\\d{2}-\\d{2})");
  private static final Pattern INCOME_SUM = Pattern.compile("收入总计\\s*[：:]\\s*([\\d,]+\\.\\d{2})");
  private static final Pattern EXPENSE_SUM = Pattern.compile("支出总计\\s*[：:]\\s*([\\d,]+\\.\\d{2})");

  private GlBankNativePdfParser() {}

  public static BankStatementDTO parse(File pdfFile) throws IOException {
    BankStatementDTO dto = new BankStatementDTO();
    StatementHeader header = new StatementHeader();
    StatementSummary summary = new StatementSummary();
    List<TransactionDTO> transactions = new ArrayList<>();

    header.setBankName("桂林银行");
    header.setCurrency("人民币");

    String text;
    try (PDDocument doc = PDDocument.load(pdfFile)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      text = stripper.getText(doc);
    }

    int seq = 0;
    String[] lines = text.split("\\r?\\n");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i].trim();
      if (line.isEmpty()) {
        continue;
      }
      Matcher m = TX_LINE.matcher(line);
      if (!m.matches() && line.matches("^\\d{4}-\\d{2}-\\d{2}\\s.*")) {
        // 户名过长时会折到下一行，向前合并直到能匹配出完整一笔
        StringBuilder merged = new StringBuilder(line);
        while (!TX_LINE.matcher(merged).matches() && i + 1 < lines.length) {
          String next = lines[++i].trim();
          if (next.isEmpty()) {
            continue;
          }
          merged.append(' ').append(next);
          if (merged.length() > 300) {
            break;
          }
        }
        m = TX_LINE.matcher(merged);
      }
      if (m.matches()) {
        TransactionDTO tx = new TransactionDTO();
        tx.setSeqNo(++seq);
        tx.setTransDate(LocalDate.parse(m.group(1)));
        tx.setCounterpartyAccount(m.group(2));
        tx.setCounterpartyName(m.group(3).trim());
        BigDecimal income = amount(m.group(4));
        BigDecimal expense = amount(m.group(5));
        if (expense.signum() > 0) {
          tx.setAmount(expense.negate());
          tx.setDirection("出");
        } else if (expense.signum() < 0) {
          // 负数支出为冲正/退款：资金回补，业务方向仍记支出
          tx.setAmount(expense.negate());
          tx.setDirection("出");
        } else if (income.signum() < 0) {
          tx.setAmount(income);
          tx.setDirection("入");
        } else {
          tx.setAmount(income);
          tx.setDirection("入");
        }
        tx.setBalance(amount(m.group(6)));
        tx.setAmountSignExplicit(true);
        transactions.add(tx);
        continue;
      }

      String compact = line.replaceAll("\\s+", "");
      Matcher mm;
      if (header.getAccountNo() == null && (mm = ACCOUNT.matcher(compact)).find()) {
        header.setAccountNo(mm.group(1));
      }
      if (header.getAccountHolder() == null && (mm = HOLDER.matcher(compact)).find()) {
        header.setAccountHolder(mm.group(1));
      }
      if (header.getStartDate() == null && (mm = START.matcher(compact)).find()) {
        header.setStartDate(LocalDate.parse(mm.group(1)));
      }
      if (header.getEndDate() == null && (mm = END.matcher(compact)).find()) {
        header.setEndDate(LocalDate.parse(mm.group(1)));
      }
      if (summary.getTotalIncome() == null && (mm = INCOME_SUM.matcher(compact)).find()) {
        summary.setTotalIncome(amount(mm.group(1)));
      }
      if (summary.getTotalExpense() == null && (mm = EXPENSE_SUM.matcher(compact)).find()) {
        summary.setTotalExpense(amount(mm.group(1)));
      }
    }

    int ic = 0;
    int ec = 0;
    BigDecimal inc = BigDecimal.ZERO;
    BigDecimal exp = BigDecimal.ZERO;
    for (TransactionDTO tx : transactions) {
      if (tx.getAmount().signum() >= 0) {
        inc = inc.add(tx.getAmount());
        ic++;
      } else {
        exp = exp.add(tx.getAmount().negate());
        ec++;
      }
    }
    if (summary.getTotalIncome() == null || summary.getTotalExpense() == null) {
      summary.setTotalIncome(inc);
      summary.setTotalExpense(exp);
    }
    summary.setIncomeCount(ic);
    summary.setExpenseCount(ec);

    dto.setHeader(header);
    dto.setSummary(summary);
    dto.setTransactions(transactions);
    dto.setRawOcrText(text);
    return dto;
  }

  private static BigDecimal amount(String s) {
    try {
      BigDecimal v = AmountNormalizer.normalize(s.replaceAll("[^0-9.,\\-]", ""));
      return v == null ? BigDecimal.ZERO : v;
    } catch (Exception e) {
      return BigDecimal.ZERO;
    }
  }
}
