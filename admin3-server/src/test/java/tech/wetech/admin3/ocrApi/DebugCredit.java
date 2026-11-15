package tech.wetech.admin3.ocrApi;

import tech.wetech.admin3.ocrApi.parser.CreditReportNativePdfParser;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Debug 征信 parser：打印各章节切分后的原始句子 + 正则匹配结果。
 */
public class DebugCredit {
  public static void main(String[] args) throws Exception {
    File pdf = new File("src/main/resources/CREDIT/credit.pdf");
    String pwd = "002836";
    String dump = Files.readString(Path.of("target/credit_dump.txt"));

    // 手动切分章节，和 parser 保持一致
    String guaranteeSection = extractSection(dump, "相关还款责任信息", "非信贷交易记录");
    String loanSection = extractSection(dump, "从未发生过逾期的账户明细如下", "相关还款责任信息");
    String ccSection = extractSection(dump, "从未逾期过的贷记卡及透支未超过60天的准贷记卡账户明细如下", "贷款");

    System.out.println("========== GUARANTEE SECTION (first 3 raw blocks) ==========");
    List<String> gBlocks = splitNumberedSentences(guaranteeSection);
    System.out.println("Total blocks: " + gBlocks.size());
    for (int i = 0; i < Math.min(3, gBlocks.size()); i++) {
      String s = gBlocks.get(i);
      System.out.println("--- Block " + i + " (" + s.length() + " chars) ---");
      System.out.println(s.substring(0, Math.min(200, s.length())));
      System.out.println("---END---");
      testGuaranteeRegex(s);
    }

    System.out.println("\n========== LOAN SECTION (total blocks) ==========");
    List<String> lBlocks = splitNumberedSentences(loanSection);
    System.out.println("Total blocks: " + lBlocks.size());

    // 检查每个 block 是发放型还是授信型
    int disbursed = 0, creditLine = 0, other = 0;
    List<String> unmatched = new ArrayList<>();
    for (String s : lBlocks) {
      if (s.contains("发放的")) disbursed++;
      else if (s.contains("授信")) creditLine++;
      else { other++; unmatched.add(s); }
    }
    System.out.println("发放型: " + disbursed + "  授信型: " + creditLine + "  其他: " + other);
    if (!unmatched.isEmpty()) {
      System.out.println("非发放/授信的句子:");
      unmatched.forEach(s -> System.out.println("  " + s.substring(0, Math.min(150, s.length()))));
    }

    // 调试正则：跑几条看看
    System.out.println("\n========== Loan regex test ==========");
    for (int i = 0; i < Math.min(3, lBlocks.size()); i++) {
      String s = lBlocks.get(i);
      System.out.println("--- Loan Block " + i + " ---");
      System.out.println(s.substring(0, Math.min(150, s.length())));
      testLoanRegex(s);
    }

    // 授信型句子单独测
    System.out.println("\n========== Credit Line regex test ==========");
    for (String s : lBlocks) {
      if (s.contains("授信")) {
        System.out.println("--- Sentence ---");
        System.out.println(s.substring(0, Math.min(200, s.length())));
        testCreditLineRegex(s);
      }
    }
  }

  static void testGuaranteeRegex(String s) {
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
      System.out.println("  MATCH!");
      for (int i = 1; i <= m.groupCount(); i++) {
        System.out.println("    g" + i + ": " + m.group(i));
      }
    } else {
      System.out.println("  NO MATCH — trying simpler regex...");
      // 尝试更简单的
      Pattern p2 = Pattern.compile(
        "(\\d{4})年(\\d{2})月(\\d{2})日[，,]\\s*" +
        "为(.+?)[（(]" +
        "证件类型[：:]\\s*(\\S+)" +
        "[，,].*?" +
        "证件号码[：:]\\s*(\\S+)[）)][，,]\\s*" +
        "在(.+?)" +
        "办理的贷款承担相关还款责任"
      );
      Matcher m2 = p2.matcher(s);
      if (m2.find()) {
        System.out.println("  PARTIAL MATCH with simple regex:");
        for (int i = 1; i <= m2.groupCount(); i++) {
          System.out.println("    g" + i + ": " + m2.group(i));
        }
      } else {
        System.out.println("  ALSO NO MATCH");
        // 直接 dump 前 100 字
        System.out.println("  TEXT START: " + s.substring(0, Math.min(200, s.length())));
      }
    }
  }

  static void testLoanRegex(String s) {
    if (!s.contains("发放的")) {
      System.out.println("  (not disbursed, skip)");
      return;
    }
    Pattern p = Pattern.compile(
      "(\\d{4})年(\\d{2})月(\\d{2})日" +
      "(.+?)" +
      "发放的" +
      "([\\d,]+)元" +
      "[（(]([^）)]*)[）)]" +
      "(.+?)?" +
      "[，,]\\s*" +
      "(?:(\\d{4})年(\\d{2})月(?:(\\d{2})日)?" +
      "到期" +
      "[，,]\\s*截至(\\d{4})年(\\d{2})月" +
      "，\\s*余额([\\d,]+)?" +
      "|" +
      "(\\d{4})年(\\d{2})月已结清)"
    );
    Matcher m = p.matcher(s);
    if (m.find()) {
      System.out.println("  MATCH!");
      for (int i = 1; i <= m.groupCount(); i++) {
        System.out.println("    g" + i + ": " + m.group(i));
      }
    } else {
      System.out.println("  NO MATCH");
      System.out.println("  TEXT START: " + s.substring(0, Math.min(200, s.length())));
    }
  }

  static void testCreditLineRegex(String s) {
    Pattern p = Pattern.compile(
      "(\\d{4})年(\\d{2})月(\\d{2})日" +
      "(.+?)" +
      "为(.+?)贷款授信" +
      "[，,]?" +
      "(?:额度有效期至(\\d{4})年(\\d{2})月(\\d{2})日)?" +
      "可循环使用[，,]?\\s*" +
      "(?:截至(\\d{4})年(\\d{2})月[，,]\\s*" +
      "信用额度([\\d,]+)元[（(]([^）)]*)[）)][，,]\\s*" +
      "余额为([\\d,]+)(?:[^。]*?当前无逾期)?" +
      "|" +
      "(\\d{4})年(\\d{2})月已结清)"
    );
    Matcher m = p.matcher(s);
    if (m.find()) {
      System.out.println("  MATCH!");
      for (int i = 1; i <= m.groupCount(); i++) {
        System.out.println("    g" + i + ": " + m.group(i));
      }
    } else {
      System.out.println("  NO MATCH — checking variants...");
      // 打印句子里的关键特征
      System.out.println("  包含'额度有效期': " + s.contains("额度有效期"));
      System.out.println("  包含'截至': " + s.contains("截至"));
      System.out.println("  包含'已结清': " + s.contains("已结清"));
      System.out.println("  TEXT: " + s);
    }
  }

  // 工具方法（反射调用不到私有方法，直接复制）
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
    String cleaned = section.replaceAll("第 \\d+ 页，共 \\d+ 页", "");
    String[] blocks = cleaned.split("(?m)(?=^\\s*\\d+\\. )");
    for (String block : blocks) {
      String trimmed = block.trim();
      if (trimmed.isEmpty()) continue;
      Matcher num = Pattern.compile("^\\s*(\\d+)\\.\\s*").matcher(trimmed);
      if (num.find()) {
        String sentence = trimmed.substring(num.end());
        sentence = sentence.replaceAll("\\r?\\n", "").replaceAll("\\s{2,}", " ").trim();
        if (!sentence.isEmpty()) result.add(sentence);
      }
    }
    return result;
  }
}
