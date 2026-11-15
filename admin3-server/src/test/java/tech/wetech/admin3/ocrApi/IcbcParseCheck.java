package tech.wetech.admin3.ocrApi;

import org.json.JSONArray;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.parser.IcbcOcrStatementParser;

import java.math.BigDecimal;
import java.util.List;

/**
 * 临时调试工具：校验工行 OCR JSON 的解析结果（笔数 / 金额 / 余额连续性）。
 */
public class IcbcParseCheck {

  public static void main(String[] args) {
    JSONArray pages = OcrSampleLoader.loadFromClasspath("ICBC", "黄总个人工行半年流水记录表.json");
    BankStatementDTO dto = IcbcOcrStatementParser.parse(pages);
    List<TransactionDTO> txs = dto.getTransactions();

    System.out.println("header=" + dto.getHeader());
    System.out.println("summary=" + dto.getSummary());
    System.out.println("count=" + txs.size());

    for (int i = 0; i < Math.min(5, txs.size()); i++) {
      System.out.println("  " + txs.get(i));
    }
    System.out.println("  ...");
    for (int i = Math.max(0, txs.size() - 3); i < txs.size(); i++) {
      System.out.println("  " + txs.get(i));
    }

    int bad = 0;
    BigDecimal prevBalance = null;
    for (TransactionDTO tx : txs) {
      if (prevBalance != null && tx.getBalance() != null && tx.getAmount() != null) {
        BigDecimal expect = prevBalance.add(tx.getAmount());
        if (expect.compareTo(tx.getBalance()) != 0) {
          bad++;
          if (bad <= 10) {
            System.out.println("MISMATCH seq=" + tx.getSeqNo() + " date=" + tx.getTransDate()
              + " prev=" + prevBalance + " amount=" + tx.getAmount()
              + " balance=" + tx.getBalance() + " expect=" + expect);
          }
        }
      }
      if (tx.getBalance() != null) prevBalance = tx.getBalance();
    }
    System.out.println("balanceMismatch=" + bad + "/" + txs.size());

    BigDecimal limit = new BigDecimal("50000");
    for (TransactionDTO tx : txs) {
      if (tx.getAmount() != null && tx.getAmount().abs().compareTo(limit) > 0
        || tx.getBalance() != null && tx.getBalance().abs().compareTo(limit) > 0) {
        System.out.println("BIG seq=" + tx.getSeqNo() + " date=" + tx.getTransDate()
          + " amount=" + tx.getAmount() + " balance=" + tx.getBalance()
          + " summary=" + tx.getSummary());
      }
    }
  }
}
