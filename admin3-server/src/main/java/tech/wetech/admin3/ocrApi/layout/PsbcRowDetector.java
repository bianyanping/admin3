package tech.wetech.admin3.ocrApi.layout;

import java.util.List;
import java.util.regex.Pattern;

public class PsbcRowDetector implements TransactionRowDetector {

  private static final Pattern DATE_LOOSE =
    Pattern.compile("\\d{4}[-年]?\\d{1,2}[-月]?\\d{1,2}日?");
  private static final Pattern AMOUNT_LOOSE =
    Pattern.compile("[+-]?[\\d,]*\\d(?:\\.\\d{2})?");

  @Override
  public boolean isRowStart(List<String> words, int i) {
    if (i + 2 >= words.size()) return false;
    // 情况 1：日期 + 日期 + 金额（记账日期 + 交易日期 + 支出金额）
    if (DATE_LOOSE.matcher(words.get(i)).matches()
      && DATE_LOOSE.matcher(words.get(i + 1)).matches()
      && AMOUNT_LOOSE.matcher(words.get(i + 2)).matches()) {
      return true;
    }
    // 情况 2：日期 + 金额 + 金额（交易日期 + 支出 + 收入）
    if (DATE_LOOSE.matcher(words.get(i)).matches()
      && AMOUNT_LOOSE.matcher(words.get(i + 1)).matches()
      && AMOUNT_LOOSE.matcher(words.get(i + 2)).matches()) {
      return true;
    }
    return false;
  }

  @Override
  public int findNextRow(List<String> words, int from) {
    for (int i = from; i < words.size(); i++) {
      if (isRowStart(words, i)) return i;
    }
    return words.size();
  }
}
