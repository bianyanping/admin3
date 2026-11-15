package tech.wetech.admin3.ocrApi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 极简 debug：直接用 dump.txt 切分 + 打印匹配失败的句子 */
public class DebugCredit2 {
  public static void main(String[] args) throws Exception {
    String dump = Files.readString(Path.of("target/credit_dump.txt"));

    String gSection = extractSection(dump, "相关还款责任信息", "非信贷交易记录");
    String lSection = extractSection(dump, "从未发生过逾期的账户明细如下", "相关还款责任信息");

    List<String> gSents = splitNumberedSentences(gSection);
    List<String> lSents = splitNumberedSentences(lSection);

    System.out.println("GUARANTEE sentences: " + gSents.size());
    for (int i = 0; i < Math.min(3, gSents.size()); i++) {
      System.out.println("\n=== G Sent " + i + " ===");
      System.out.println(gSents.get(i));
      testGuarantee(gSents.get(i));
    }

    System.out.println("\n\nLOAN sentences: " + lSents.size());
    int matched = 0, unmatched = 0;
    for (String s : lSents) {
      if (testLoanSilent(s)) matched++;
      else {
        unmatched++;
        if (unmatched <= 5) {
          System.out.println("UNMATCHED: " + s.substring(0, Math.min(200, s.length())));
        }
      }
    }
    System.out.println("Loan matched=" + matched + " unmatched=" + unmatched);
  }

  static boolean testLoanSilent(String s) {
    // 发放型
    Pattern p1 = Pattern.compile(
      "(\\d{4})年(\\d{2})月(\\d{2})日" +
      "(.+?)" + "发放的" +
      "([\\d,]+)元" +
      "[（(]([^）)]*)[）)]" +
      "(.+?)?" +
      "[，,]\\s*" +
      "(?:(\\d{4})年(\\d{2})月(?:(\\d{2})日)?" + "到期" +
      "[，,]\\s*截至(\\d{4})年(\\d{2})月" + "，\\s*余额([\\d,]+)?" +
      "|" +
      "(\\d{4})年(\\d{2})月已结清)"
    );
    if (p1.matcher(s).find()) return true;

    // 授信型
    Pattern p2 = Pattern.compile(
      "(\\d{4})年(\\d{2})月(\\d{2})日" +
      "(.+?)" + "为(.+?)贷款授信" +
      "[，,]?" +
      "(?:额度有效期至(\\d{4})年(\\d{2})月(\\d{2})日)?" +
      "可循环使用[，,]?\\s*" +
      "(?:截至(\\d{4})年(\\d{2})月[，,]\\s*" +
      "信用额度([\\d,]+)元[（(]([^）)]*)[）)][，,]\\s*" +
      "余额为([\\d,]+)(?:[^。]*?当前无逾期)?" +
      "|" +
      "(\\d{4})年(\\d{2})月已结清)"
    );
    return p2.matcher(s).find();
  }

  static void testGuarantee(String s) {
    Pattern p = Pattern.compile(
      "(\\d{4})年(\\d{2})月(\\d{2})日[，,]\\s*" +
      "为(.+?)" +
      "[（(]证件类型[：:]\\s*(\\S+)[，,].*?证件号码[：:]\\s*(\\S+)[）)][，,]\\s*" +
      "在(.+?)" +
      "办理的贷款承担相关还款责任[，,]\\s*" +
      "责任人类型为(\\S+)[，,]\\s*" +
      "相关还款责任金额([\\d,]+)" +
      "(?:[（(]保证合同编号[：:]\\s*(\\S+)[）)])?[，,]\\s*" +
      "截至(\\d{4})年(\\d{2})月(\\d{2})日[，,]\\s*" +
      "贷款余额([\\d,]+)" +
      "(?:[（(]([^）)]*)[）)])?[。.]?"
    );
    Matcher m = p.matcher(s);
    if (m.find()) {
      System.out.println("  MATCH! groups=" + m.groupCount());
    } else {
      System.out.println("  NO MATCH");
      // 逐步试
      String[] steps = {
        "(\\d{4})年(\\d{2})月(\\d{2})日",
        "(\\d{4})年(\\d{2})月(\\d{2})日[，,]\\s*为(.+?)[（(]",
        "(\\d{4})年(\\d{2})月(\\d{2})日[，,]\\s*为(.+?)[（(]证件类型[：:]\\s*(\\S+)[，,]",
        "证件号码",
        "相关还款责任金额",
        "保证合同编号",
        "截至",
        "贷款余额"
      };
      for (String step : steps) {
        boolean found = Pattern.compile(step).matcher(s).find();
        System.out.println("    " + step + " → " + found);
      }
    }
  }

  private static String extractSection(String text, String fromRegex, String toRegex) {
    Pattern from = Pattern.compile(fromRegex);
    Matcher fm = from.matcher(text);
    if (!fm.find()) return "";
    int start = fm.end();
    Pattern to = Pattern.compile(toRegex);
    Matcher tm = to.matcher(text.substring(start));
    int end = tm.find() ? start + tm.start() : text.length();
    return text.substring(start, end);
  }

  private static List<String> splitNumberedSentences(String section) {
    List<String> result = new ArrayList<>();
    String flat = section.replaceAll("\\r?\\n", "").replaceAll("\\s{2,}", " ").trim();
    flat = flat.replaceAll("第\\s*\\d+\\s*页，共\\s*\\d+\\s*页", "");

    Pattern numPattern = Pattern.compile("(\\d+)\\.\\s*");
    Matcher m = numPattern.matcher(flat);
    List<Integer> positions = new ArrayList<>();
    List<Integer> numbers = new ArrayList<>();
    while (m.find()) {
      positions.add(m.start());
      numbers.add(Integer.parseInt(m.group(1)));
    }
    for (int i = 0; i < positions.size(); i++) {
      int numLen = String.valueOf(numbers.get(i)).length();
      int startContent = positions.get(i) + numLen + 2;
      int endContent = (i + 1 < positions.size()) ? positions.get(i + 1) : flat.length();
      String sentence = flat.substring(startContent, endContent).trim();
      if (!sentence.isEmpty()) {
        result.add(numbers.get(i) + ". " + sentence);
      }
    }
    return result;
  }
}
