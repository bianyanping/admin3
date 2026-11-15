package tech.wetech.admin3.ocrApi.layout;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class SeqDateLooseRowDetector implements TransactionRowDetector {

  private static final Pattern SEQ_PATTERN = Pattern.compile("\\d{1,3}|[A-Z]\\d?");
  private static final Pattern DATE_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern AMOUNT_LOOK = Pattern.compile("[+-]?[\\dTlIOS,]+(?:[.,][\\dTlIOS]{3})*[.,][\\dTlIOS]{2}");
  private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
  private static final Pattern SEQ = Pattern.compile("\\d{1,3}|[A-Z]\\d?|[a-z]");
  private static final Set<String> PAGE_MARKERS = Set.of("共8页第1页", "共8页第2页", "共8页第3页", "共8页第4页", "共8页第5页", "共8页第6页", "共8页第7页", "共8页第8页");
  private static final Pattern TIME = Pattern.compile("\\d{2}:\\d{2}:\\d{2}");

  @Override
  public boolean isRowStart(List<String> words, int i) {
    // 1. 序号 + 日期
    if (i + 1 < words.size() && SEQ.matcher(words.get(i)).matches() && DATE.matcher(words.get(i + 1)).matches()) {
      return true;
    }
    // 2. 日期开头：前面不能是序号/日期/金额/时间
    if (DATE.matcher(words.get(i)).matches()) {
      if (i > 0) {
        String prev = words.get(i - 1);
        // 前一个是序号或日期，才是行内日期，返回 false
        if (SEQ.matcher(prev).matches() || DATE.matcher(prev).matches()) {
          return false;
        }
        // 时间、金额前面也可能是新行日期，不排除
      }
      if (i + 1 < words.size()) {
        String next = words.get(i + 1);
        return AMOUNT_LOOK.matcher(next).matches() || TIME.matcher(next).matches();
      }
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
