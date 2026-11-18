package tech.wetech.admin3.ocrApi.parser;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import tech.wetech.admin3.ocrApi.model.CreditReport;
import tech.wetech.admin3.ocrApi.model.CreditReportItem;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 征信报告 PDF 原生解析器（pdfbox + 正则，免 OCR）。
 *
 * <p>支持加密 PDF：传入密码即可自动解密。
 * 输出为 {@link CreditReport}（header） + 关联的 {@link CreditReportItem} 列表。
 *
 * <p>征信报告条目全是"句子式"，pdfbox 会把长句折成多行。核心策略是：
 * 先按章节标题 + 序号把文本分块，再在每块内按 "数字. " 切分出完整条目。
 */
public final class CreditReportNativePdfParser {

  private CreditReportNativePdfParser() {}

  /**
   * 解析加密 PDF
   */
  public static CreditReport parse(File pdf, String password) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf, password)) {
      return parseDoc(doc, pdf);
    }
  }

  /**
   * 解析无密码 PDF
   */
  public static CreditReport parse(File pdf) throws IOException {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return parseDoc(doc, pdf);
    }
  }

  // ==================== 内部实现 ====================

  private static CreditReport parseDoc(PDDocument doc, File pdfFile) throws IOException {
    String rawText = extractAllPages(doc);

    CreditReport report = new CreditReport();
    report.setRawText(rawText);
    report.setRawPdfPath(pdfFile.getAbsolutePath());

    // 1. 基本信息
    parseHeader(rawText, report);

    // 2. 概要统计
    parseSummary(rawText, report);

    // 3. 分章节抽取条目
    String creditCardSection = extractSection(rawText,
      "从未逾期过的贷记卡及透支未超过60天的准贷记卡账户明细如下",
      "贷款");
    String loanSection = extractSection(rawText,
      "从未发生过逾期的账户明细如下",
      "相关还款责任信息");
    String guaranteeSection = extractSection(rawText,
      "相关还款责任信息",
      "非信贷交易记录");
    String orgQuerySection = extractSection(rawText,
      "机构查询记录明细",
      "个人查询记录明细");
    String personQuerySection = extractSection(rawText,
      "个人查询记录明细",
      "第 \\d+ 页，共 \\d+ 页");

    // 4. 解析信用卡条目
    for (String sentence : splitNumberedSentences(creditCardSection)) {
      CreditReportItem item = parseCreditCard(sentence);
      if (item != null) report.addItem(item);
    }

    // 5. 解析贷款条目
    for (String sentence : splitNumberedSentences(loanSection)) {
      CreditReportItem item = parseLoan(sentence);
      if (item != null) report.addItem(item);
    }

    // 6. 解析担保条目
    for (String sentence : splitNumberedSentences(guaranteeSection)) {
      CreditReportItem item = parseGuarantee(sentence);
      if (item != null) report.addItem(item);
    }

    // 7. 解析查询记录（表格，单独处理）
    for (CreditReportItem item : parseOrgQueries(orgQuerySection)) {
      report.addItem(item);
    }
    for (CreditReportItem item : parsePersonQueries(personQuerySection)) {
      report.addItem(item);
    }

    return report;
  }

  // ==================== PDF 文本提取 ====================

  private static String extractAllPages(PDDocument doc) throws IOException {
    PDFTextStripper stripper = new PDFTextStripper();
    stripper.setSortByPosition(true);
    StringBuilder sb = new StringBuilder();
    int total = doc.getNumberOfPages();
    for (int p = 1; p <= total; p++) {
      stripper.setStartPage(p);
      stripper.setEndPage(p);
      sb.append(stripper.getText(doc)).append('\n');
    }
    return sb.toString();
  }

  // ==================== Header 解析 ====================

  private static final Pattern P_REPORT_NO = Pattern.compile("报告编号[：:]\\s*(\\d+)");
  private static final Pattern P_REPORT_TIME = Pattern.compile("报告时间[：:]\\s*(\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2})");
  private static final Pattern P_NAME_LINE = Pattern.compile(
    "姓名[：:]\\s*(\\S+)\\s+" +
    "证件类型[：:]\\s*(\\S+)\\s+" +
    "证件号码[：:]\\s*(\\S+)\\s*" +
    "(\\S+)?"  // 婚姻状况（可选）
  );

  private static void parseHeader(String text, CreditReport report) {
    Matcher m;

    m = P_REPORT_NO.matcher(text);
    if (m.find()) report.setReportNo(m.group(1));

    m = P_REPORT_TIME.matcher(text);
    if (m.find()) report.setReportTime(LocalDateTime.parse(m.group(1),
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

    m = P_NAME_LINE.matcher(text);
    if (m.find()) {
      report.setName(m.group(1));
      report.setIdType(m.group(2));
      report.setIdNo(m.group(3));
      report.setMaritalStatus(m.group(4));
    }
  }

  // ==================== 概要统计 ====================

  /**
   * 概要统计段落（pdfbox 抽出来可能折行）：
   * <pre>
   *           购房  其他
   * 账户数     8    5   73 --
   * 未结清/未销户 3   --   6  --
   * 为个人 为企业
   * 相关还款责任 --   20
   * </pre>
   * 直接用正则从原始文本里挖数字。
   */
  private static void parseSummary(String text, CreditReport report) {
    // 信用卡账户数 = "账户数" 行里第一个有效数字（排除 购房/其他 那个"其他"列的解释）
    // 从信息概要区域找 "账户数 8 5 73" 这样的序列
    // 简单策略：正则找 "账户数" 附近的三个数字

    // 信用卡数：概要说明文字有 "购房贷款..." 那个注释，信用卡在"其他"列
    // 看 dump 文件 line 15: "账户数 8 5 73 --"  —— 8=购房贷款, 5=信用卡, 73=其他
    // 但 line 17: "未结清/未销户账户数 3 -- 6 --" —— 3=购房贷未结清, --=信用卡全部结清, 6=其他未结清

    // 更稳的方式：从概要表格提取数字序列
    Pattern pAccounts = Pattern.compile("账户数\\s+([\\-\\d]+)\\s+([\\-\\d]+)\\s+([\\-\\d]+)\\s+([\\-\\d]+)");
    Matcher m = pAccounts.matcher(text);
    if (m.find()) {
      // 四列分别是 购房贷 / 信用卡 / 其他 / (空)
      report.setCreditCardCount(safeInt(m.group(2)));
      report.setLoanCount(safeInt(m.group(1)) + safeInt(m.group(3)));
    }

    Pattern pActive = Pattern.compile("未结清/未销户账户数\\s+([\\-\\d]+)\\s+([\\-\\d]+)\\s+([\\-\\d]+)\\s+([\\-\\d]+)");
    m = pActive.matcher(text);
    if (m.find()) {
      report.setActiveCreditCardCount(safeInt(m.group(2)));
      report.setActiveLoanCount(safeInt(m.group(1)) + safeInt(m.group(3)));
    }

    // 为企业担保数
    Pattern pGuarantee = Pattern.compile("相关还款责任账户数\\s+([\\-\\d]+)\\s+([\\-\\d]+)");
    m = pGuarantee.matcher(text);
    if (m.find()) {
      report.setGuaranteeForCompanyCount(safeInt(m.group(2)));
    }
  }

  private static Integer safeInt(String s) {
    if (s == null || s.trim().isEmpty() || s.contains("-")) return null;
    try { return Integer.parseInt(s.trim()); }
    catch (NumberFormatException e) { return null; }
  }

  // ==================== 章节抽取 ====================

  /**
   * 从全文中截取 fromPattern 之后、toPattern 之前的文本
   */
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

  // ==================== 按序号切分句子 ====================

  /**
   * 把章节文本按 "N. " 序号切分，每一段拼成一条完整句子。
   *
   * <p>核心策略：先把整个 section 的所有换行符去掉（消除 pdfbox 折行），
   * 得到一行大字符串，然后用 matcher 找每个 "数字. " 的起始位置，
   * 相邻两个位置之间就是一条完整句子，同时拿到 itemNo。
   */
  private static List<String> splitNumberedSentences(String section) {
    List<String> result = new ArrayList<>();

    // 1. 把所有换行符干掉，消除 pdfbox 折行
    String flat = section
      .replaceAll("\\r?\\n", "")
      .replaceAll("\\s{2,}", " ")
      .trim();

    // 2. 干掉页码
    flat = flat.replaceAll("第\\s*\\d+\\s*页，共\\s*\\d+\\s*页", "");

    // 3. 找所有 "N. " 的出现位置
    Pattern numPattern = Pattern.compile("(\\d+)\\.\\s*");
    Matcher m = numPattern.matcher(flat);

    List<Integer> positions = new ArrayList<>();
    List<Integer> numbers = new ArrayList<>();
    while (m.find()) {
      positions.add(m.start());
      numbers.add(Integer.parseInt(m.group(1)));
    }

    // 4. 按位置切分
    for (int i = 0; i < positions.size(); i++) {
      int numLen = String.valueOf(numbers.get(i)).length();
      int startContent = positions.get(i) + numLen + 2; // "N. " 之后

      int endContent = (i + 1 < positions.size()) ? positions.get(i + 1) : flat.length();

      String sentence = flat.substring(startContent, endContent).trim();
      if (!sentence.isEmpty()) {
        // 把 itemNo 写进 sentence 开头（方便 extractItemNo）
        result.add(numbers.get(i) + ". " + sentence);
      }
    }

    return result;
  }

  // ==================== 信用卡解析 ====================

  /**
   * 信用卡句子两种形式：
   * 1. 未结清："...发放的贷记卡（XX账户[，卡片尾号：XXXX]）。截至YYYY年MM月，信用额度XXX，已使用额度XXX。"
   * 2. 已销户："...发放的贷记卡（XX账户[，卡片尾号：XXXX]），YYYY年MM月销户。"
   */
  private static final Pattern P_CREDIT_CARD = Pattern.compile(
    "(\\d{4})年(\\d{2})月(\\d{2})日" +
    "(.+?)" +                              // 机构名
    "发放的贷记卡" +
    "（([^）]*)）" +                         // 账户类型 + 尾号
    "[，.]?\\s*" +
    "(?:(\\d{4})年(\\d{2})月销户)?" +       // 销户日期（可选）
    "[，.]?\\s*" +
    "(?:.*?截至(\\d{4})年(\\d{2})月" +
    "，\\s*信用额度([\\d,]+)" +
    "[，,]?\\s*已使用额度([\\d,]+))?"        // 额度部分（可选）
  );

  private static CreditReportItem parseCreditCard(String sentence) {
    Matcher m = P_CREDIT_CARD.matcher(sentence);
    if (!m.find()) return null;

    CreditReportItem item = new CreditReportItem();
    item.setItemType("CREDIT_CARD");
    item.setItemNo(extractItemNo(sentence));
    item.setOpenDate(ymd(m.group(1), m.group(2), m.group(3)));
    item.setOrgName(m.group(4).trim());
    item.setProductType("贷记卡");

    // 解析账户类型括号内容："人民币账户" 或 "美元账户，卡片尾号：9548"
    String accInfo = m.group(5);
    if (accInfo != null) {
      // 货币
      if (accInfo.contains("美元")) item.setCurrency("美元");
      else if (accInfo.contains("人民币")) item.setCurrency("人民币");
      // 尾号
      Matcher tail = Pattern.compile("尾号[：:]?\\s*(\\d+)").matcher(accInfo);
      if (tail.find()) item.setTailNo(tail.group(1));
    }

    // 状态 & 金额
    if (m.group(6) != null && m.group(7) != null) {
      item.setStatus("已销户");
      item.setExpiryDate(ymd(m.group(6), m.group(7), "01"));
    } else {
      item.setStatus("未结清");
    }

    if (m.group(8) != null) {
      item.setAmount(parseAmount(m.group(10)));
      item.setUsedAmount(parseAmount(m.group(11)));
    }

    item.setRawSentence(sentence);
    return item;
  }

  // ==================== 贷款解析 ====================

  /**
   * 贷款句子：
   * <p>发放型：
   * "...发放的XXX,XXX元（人民币）XXX贷款，YYYY年MM月DD日到期。截至YYYY年MM月，余额XXX,XXX。"
   * 或 "...发放的XXX,XXX元（人民币）XXX贷款，YYYY年MM月已结清。"
   * <p>授信型：
   * "...为XXX贷款授信，额度有效期至YYYY年MM月DD日，可循环使用。截至YYYY年MM月，信用额度XXX元（人民币），余额为0，当前无逾期。"
   * 或 "...为XXX贷款授信，可循环使用。YYYY年MM月已结清。"
   */
  private static CreditReportItem parseLoan(String sentence) {
    CreditReportItem item = new CreditReportItem();
    item.setItemType("LOAN");
    item.setItemNo(extractItemNo(sentence));
    item.setRawSentence(sentence);

    // 先判断是"发放"还是"授信"
    if (sentence.contains("发放的")) {
      return parseDisbursedLoan(sentence, item);
    } else if (sentence.contains("授信")) {
      return parseCreditLine(sentence, item);
    }
    return null;
  }

  /**
   * 发放型贷款——分步提取（比一个巨大正则更稳）。
   */
  private static CreditReportItem parseDisbursedLoan(String sentence, CreditReportItem item) {
    // 1. 起始日期
    Matcher m;
    m = Pattern.compile("(\\d{4})年(\\d{2})月(\\d{2})日").matcher(sentence);
    if (!m.find()) return null;
    item.setOpenDate(ymd(m.group(1), m.group(2), m.group(3)));

    // 2. "发放的" 之前是机构名 + 之后到 "元" 是金额
    m = Pattern.compile("(.+?)发放的([\\d,]+)元").matcher(sentence);
    if (!m.find()) return null;
    String orgAndAmount = m.group(1);
    item.setAmount(parseAmount(m.group(2)));
    // 清理：去掉开头的 "N. YYYY年MM月DD日"
    String org = orgAndAmount
      .replaceFirst("^\\d+\\.\\s*", "")                    // 去掉序号前缀 "1. "
      .replaceFirst("^\\d{4}年\\d{2}月\\d{2}日\\s*", "")  // 去掉日期前缀
      .trim();
    item.setOrgName(org);

    // 3. 币种
    m = Pattern.compile("[（(]([^）)]*)[）)]").matcher(sentence);
    if (m.find()) item.setCurrency(parseCurrency(m.group(1)));

    // 4. 贷款类型（币种后、下一个逗号之前）
    // 句子模式：...元（人民币）个人经营性贷款，YYYY年MM月DD日到期...
    int afterCurrencyEnd = 0;
    Matcher m2 = Pattern.compile("[（(][^）)]*[）)]").matcher(sentence);
    while (m2.find()) afterCurrencyEnd = m2.end();
    String afterCurrency = sentence.substring(afterCurrencyEnd);
    m = Pattern.compile("(.+?)[，,]").matcher(afterCurrency);
    if (m.find()) {
      String type = m.group(1).trim();
      item.setProductType(type);
    }

    // 5. 状态：有"到期" = 未结清，有"已结清" = 已结清
    if (sentence.contains("到期")) {
      item.setStatus("未结清");
      // 到期日期
      m = Pattern.compile("(\\d{4})年(\\d{2})月(?:(\\d{2})日)?到期").matcher(sentence);
      if (m.find()) {
        String day = m.group(3) != null ? m.group(3) : "01";
        item.setExpiryDate(ymd(m.group(1), m.group(2), day));
      }
      // 余额
      m = Pattern.compile("截至(\\d{4})年(\\d{2})月[，,]\\s*余额([\\d,]+)").matcher(sentence);
      if (m.find()) {
        item.setBalance(parseAmount(m.group(3)));
      }
    } else if (sentence.contains("已结清")) {
      item.setStatus("已结清");
      m = Pattern.compile("(\\d{4})年(\\d{2})月已结清").matcher(sentence);
      if (m.find()) {
        item.setExpiryDate(ymd(m.group(1), m.group(2), "01"));
      }
    }

    return item;
  }

  /**
   * 授信型贷款——分步提取。
   */
  private static CreditReportItem parseCreditLine(String sentence, CreditReportItem item) {
    Matcher m;

    // 1. 起始日期
    m = Pattern.compile("(\\d{4})年(\\d{2})月(\\d{2})日").matcher(sentence);
    if (!m.find()) return null;
    item.setOpenDate(ymd(m.group(1), m.group(2), m.group(3)));

    // 2. 机构名 + 贷款类型（"为XXX贷款授信"）
    m = Pattern.compile("为(.+?)贷款授信").matcher(sentence);
    if (!m.find()) return null;
    item.setProductType(m.group(1).trim());

    // 机构名：允许 "N. YYYY年MM月DD日 XXX银行为..." —— 同时 strip 序号前缀和日期前缀
    m = Pattern.compile("^(?:\\d+\\.\\s*)?\\d{4}年\\d{2}月\\d{2}日\\s*(.+?)为").matcher(sentence);
    if (m.find()) item.setOrgName(m.group(1).trim());

    // 3. 额度有效期（可选）
    m = Pattern.compile("额度有效期至(\\d{4})年(\\d{2})月(\\d{2})日").matcher(sentence);
    if (m.find()) item.setExpiryDate(ymd(m.group(1), m.group(2), m.group(3)));

    // 4. 状态
    if (sentence.contains("已结清")) {
      item.setStatus("已结清");
      m = Pattern.compile("(\\d{4})年(\\d{2})月已结清").matcher(sentence);
      if (m.find()) item.setExpiryDate(ymd(m.group(1), m.group(2), "01"));
      return item;
    }

    // 未结清：找"截至...信用额度...余额..."
    if (sentence.contains("截至")) {
      item.setStatus("未结清");
      m = Pattern.compile("信用额度([\\d,]+)元[（(]([^）)]*)[）)][，,]?\\s*余额为([\\d,]+)").matcher(sentence);
      if (m.find()) {
        item.setAmount(parseAmount(m.group(1)));
        item.setCurrency(parseCurrency(m.group(2)));
        item.setBalance(parseAmount(m.group(3)));
      }
    }

    return item;
  }

  // ==================== 担保解析 ====================

  /**
   * 担保句子——分步提取。
   */
  private static CreditReportItem parseGuarantee(String sentence) {
    CreditReportItem item = new CreditReportItem();
    item.setItemType("GUARANTEE");
    item.setItemNo(extractItemNo(sentence));
    item.setRawSentence(sentence);

    Matcher m;

    // 1. 起始日期
    m = Pattern.compile("(\\d{4})年(\\d{2})月(\\d{2})日").matcher(sentence);
    if (!m.find()) return null;
    item.setOpenDate(ymd(m.group(1), m.group(2), m.group(3)));

    // 2. 企业名："为" 到 "（证件类型"
    m = Pattern.compile("为(.+?)[（(]证件类型").matcher(sentence);
    if (m.find()) item.setGuaranteeCompanyName(m.group(1).trim());

    // 3. 企业证件类型（原文形如「（证件类型：中征码，证件号码：CN4503MAA7WPRY67）」，需在逗号/括号处截断）
    m = Pattern.compile("证件类型[：:]\\s*([^\\s，,；;）)]+)").matcher(sentence);
    if (m.find()) item.setGuaranteeCompanyIdType(m.group(1));

    // 4. 企业证件号码
    m = Pattern.compile("证件号码[：:]\\s*([^\\s，,；;）)]+)").matcher(sentence);
    if (m.find()) item.setGuaranteeCompanyIdNo(m.group(1));

    // 5. 银行名："在" 到 "办理的贷款"
    m = Pattern.compile("在(.+?)办理的贷款").matcher(sentence);
    if (m.find()) item.setOrgName(m.group(1).trim());

    // 6. 责任人类型（保证人）
    m = Pattern.compile("责任人类型为([^\\s，,；;）)]+)").matcher(sentence);
    if (m.find()) item.setResponsibilityType(m.group(1));

    // 7. 相关还款责任金额
    m = Pattern.compile("相关还款责任金额([\\d,]+)").matcher(sentence);
    if (m.find()) item.setAmount(parseAmount(m.group(1)));

    // 8. 保证合同编号（可选）
    m = Pattern.compile("保证合同编号[：:]\\s*([^\\s，,；;）)]+)").matcher(sentence);
    if (m.find()) item.setContractNo(m.group(1));

    // 9. 截至日期
    m = Pattern.compile("截至(\\d{4})年(\\d{2})月(\\d{2})日").matcher(sentence);
    if (m.find()) {
      // 可以当作合同有效截止日期
    }

    // 10. 贷款余额
    m = Pattern.compile("贷款余额([\\d,]+)").matcher(sentence);
    if (m.find()) item.setBalance(parseAmount(m.group(1)));

    // 11. 余额币种（可选，一般是"（人民币元）"）
    m = Pattern.compile("贷款余额[\\d,]+[（(]([^）)]*)[）)]").matcher(sentence);
    if (m.find()) item.setCurrency(parseCurrency(m.group(1)));

    return item;
  }

  // ==================== 查询记录解析 ====================

  /**
   * 查询记录是表格行，pdfbox 会把长机构名 / 查询原因折成多行。
   * <p>策略：按行遍历，找 "日期行" 开头 = 编号 + YYYY年MM月DD日。
   * 从该行开始，连续收集后续行直到下一个编号/日期出现，拼起来就是完整的机构名 + 查询原因。
   */
  private static List<CreditReportItem> parseOrgQueries(String section) {
    return parseQueries(section, "ORG_QUERY");
  }

  private static List<CreditReportItem> parsePersonQueries(String section) {
    return parseQueries(section, "PERSON_QUERY");
  }

  private static List<CreditReportItem> parseQueries(String section, String type) {
    List<CreditReportItem> result = new ArrayList<>();
    if (section == null || section.isBlank()) return result;

    String[] rawLines = section.split("\\r?\\n");
    List<String> lines = new ArrayList<>();
    for (String l : rawLines) {
      String t = l.trim();
      if (!t.isEmpty() && !t.matches("第 \\d+ 页，共 \\d+ 页")) {
        lines.add(t);
      }
    }

    // 表格标题行："编号 查询日期 查询机构 查询原因" —— 跳过
    int idx = 0;
    while (idx < lines.size() && !isQueryDataStart(lines.get(idx))) idx++;

    while (idx < lines.size()) {
      String line = lines.get(idx);
      // 匹配 "编号 日期" 起始
      Pattern p = Pattern.compile("^(\\d+)\\s+(\\d{4})年(\\d{2})月(\\d{2})日");
      Matcher m = p.matcher(line);
      if (!m.find()) { idx++; continue; }

      int no = Integer.parseInt(m.group(1));
      LocalDate date = ymd(m.group(2), m.group(3), m.group(4));
      // 取编号 + 日期后面的部分
      String rest = line.substring(m.end()).trim();

      // 往后收集折行：只要下一行不以 "数字+日期" 开头、也不是空行、也不是"第X页"，就拼起来
      idx++;
      while (idx < lines.size()) {
        String next = lines.get(idx);
        if (p.matcher(next).find()) break;  // 下一条开始
        rest += next;
        idx++;
      }

      // 现在 rest 是 "机构名 查询原因" —— 但机构名也可能包含空格/标点
      // 查询原因是固定枚举值之一：贷后管理 / 贷款审批 / 担保资格审查 / 信用卡审批 / 本人查询 / 特约商户实名审查 / 法人代表、负责人、高管等资信审查
      // 用原因做分割点
      String[] reasons = {
        "法人代表、负责人、高管等资信审查", "担保资格审查", "贷后管理",
        "贷款审批", "信用卡审批", "特约商户实名审查", "本人查询"
      };
      String matchedReason = null;
      int reasonIdx = -1;
      for (String r : reasons) {
        int pos = rest.lastIndexOf(r);
        if (pos >= 0) {
          if (reasonIdx == -1 || pos > reasonIdx) {
            reasonIdx = pos;
            matchedReason = r;
          }
        }
      }

      CreditReportItem item = new CreditReportItem();
      item.setItemType(type);
      item.setItemNo(no);
      item.setQueryDate(date);
      item.setQueryReason(matchedReason);
      item.setQuerySubject("ORG_QUERY".equals(type) ? "机构" : "本人");

      if (reasonIdx >= 0) {
        String org = rest.substring(0, reasonIdx).trim();
        item.setOrgName(org);
      } else {
        item.setOrgName(rest.trim());
      }

      result.add(item);
    }

    return result;
  }

  private static boolean isQueryDataStart(String line) {
    return Pattern.matches("^\\d+\\s+\\d{4}年\\d{2}月\\d{2}日.*", line);
  }

  // ==================== 公共工具 ====================

  private static Integer extractItemNo(String sentence) {
    Matcher m = Pattern.compile("^(\\d+)\\.").matcher(sentence);
    return m.find() ? Integer.parseInt(m.group(1)) : null;
  }

  private static LocalDate ymd(String y, String mo, String d) {
    return LocalDate.of(Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
  }

  private static BigDecimal parseAmount(String raw) {
    if (raw == null) return null;
    String cleaned = raw.replace(",", "").replace(",", "").trim();
    if (cleaned.isEmpty()) return null;
    try { return new BigDecimal(cleaned); }
    catch (NumberFormatException e) { return null; }
  }

  private static String parseCurrency(String raw) {
    if (raw == null) return null;
    if (raw.contains("美元")) return "美元";
    if (raw.contains("人民币")) return "人民币";
    return raw.trim();
  }

  private static String trimLoanType(String raw) {
    if (raw == null) return null;
    // 去掉末尾的 "贷款" 两字（避免重复："个人经营性贷款贷款"）
    String t = raw.trim();
    // 句子里格式是 "XXX元（人民币）XXX贷款，" —— XXX贷款 是类型
    // 正则已经把 "贷款" 之前的内容抓到了
    return t;
  }
}
