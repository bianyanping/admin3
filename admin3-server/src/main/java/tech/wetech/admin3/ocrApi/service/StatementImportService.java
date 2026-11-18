package tech.wetech.admin3.ocrApi.service;

import com.baidu.aip.ocr.AipOcr;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.ocrApi.BankStatementParserFacade;
import tech.wetech.admin3.ocrApi.BaiduOcr;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.parser.AbcHxNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.CcbNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.IcbcNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.IcbcOcrStatementParser;
import tech.wetech.admin3.ocrApi.parser.GlBankNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.GxrcbNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.NativePdfStatementParser;
import tech.wetech.admin3.ocrApi.parser.PsbcNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.WechatNativePdfParser;
import tech.wetech.admin3.sys.repository.UserRepository;
import tech.wetech.admin3.sys.service.StorageService;
import tech.wetech.admin3.sys.service.dto.StorageFileDTO;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 上传文件导入服务：自动判断 PDF 是原生文本层还是扫描件，然后选择解析链路并入库。
 *
 * <p>判断规则（{@link #looksScanned}）：
 * <ul>
 *   <li>文本层去空白后不足 60 字符 → 扫描件；</li>
 *   <li>平均每页不足 40 字符 → 扫描件（扫描件通常只有零星水印文字）；</li>
 *   <li>文本层中找不到任何日期 → 扫描件。</li>
 * </ul>
 *
 * <p>原生 PDF：PDFBox 抽文本 → 银行识别 → 对应原生解析器；
 * 扫描件：逐页渲染 200 DPI → 百度 OCR 表格接口（第 1 页追加通用 OCR 取户名）→ 对应解析器。
 */
@Service
public class StatementImportService {

  private static final Logger log = LoggerFactory.getLogger(StatementImportService.class);

  /** 原生 PDF 中小于此字符数视为扫描件 */
  private static final int MIN_TEXT_CHARS = 60;
  /** 原生 PDF 每页平均字符数下限 */
  private static final int MIN_CHARS_PER_PAGE = 40;
  /** 扫描件渲染 DPI */
  private static final int OCR_DPI = 300;

  private static final Pattern DATE = Pattern.compile("\\d{4}[-年]\\d{1,2}[-月]\\d{1,2}");

  /** 银行关键字 → 银行编码（顺序敏感：先具体后泛指） */
  private static final Map<String, String> BANK_KEYWORDS = new LinkedHashMap<>();

  static {
    BANK_KEYWORDS.put("中国工商银行", "ICBC");
    BANK_KEYWORDS.put("工商银行", "ICBC");
    BANK_KEYWORDS.put("工行", "ICBC");
    BANK_KEYWORDS.put("中国邮政储蓄银行", "PSBC");
    BANK_KEYWORDS.put("邮政储蓄", "PSBC");
    BANK_KEYWORDS.put("邮储", "PSBC");
    BANK_KEYWORDS.put("桂林国民村镇银行", "GLGMCZYH");
    BANK_KEYWORDS.put("国民村镇银行", "GLGMCZYH");
    BANK_KEYWORDS.put("村镇银行", "GLGMCZYH");
    BANK_KEYWORDS.put("桂林银行", "GLYH");
    BANK_KEYWORDS.put("广西农商联合银行", "GXRCB");
    BANK_KEYWORDS.put("广西农村商业银行", "GXRCB");
    BANK_KEYWORDS.put("广西农商", "GXRCB");
    BANK_KEYWORDS.put("农商联合银行", "GXRCB");
    BANK_KEYWORDS.put("农村商业银行", "GXRCB");
    BANK_KEYWORDS.put("农商", "GXRCB");
    BANK_KEYWORDS.put("中国农业银行", "ABCHX");
    BANK_KEYWORDS.put("农业银行", "ABCHX");
    BANK_KEYWORDS.put("农行", "ABCHX");
    BANK_KEYWORDS.put("中国建设银行", "CCB");
    BANK_KEYWORDS.put("建设银行", "CCB");
    BANK_KEYWORDS.put("建行", "CCB");
    BANK_KEYWORDS.put("微信支付交易明细证明", "WECHAT");
    BANK_KEYWORDS.put("微信支付", "WECHAT");
    BANK_KEYWORDS.put("财付通", "WECHAT");
  }

  private final BankStatementService bankStatementService;
  private final BankStatementParserFacade parserFacade;
  private final StorageService storageService;
  private final UserRepository userRepository;

  public StatementImportService(BankStatementService bankStatementService,
                                BankStatementParserFacade parserFacade,
                                StorageService storageService,
                                UserRepository userRepository) {
    this.bankStatementService = bankStatementService;
    this.parserFacade = parserFacade;
    this.storageService = storageService;
    this.userRepository = userRepository;
  }

  /**
   * 解析上传的 PDF 并入库。
   *
   * @param file       上传的 PDF
   * @param bankCode   银行编码（可选，传了就不再自动识别）
   * @param forceOcr   true=强制走 OCR（原生 PDF 也可强制）
   */
  public ImportResult importPdf(MultipartFile file, String bankCode, boolean forceOcr) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "上传文件为空");
    }
    String fileName = file.getOriginalFilename();
    if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "仅支持 PDF 文件，当前文件：" + fileName);
    }
    byte[] pdfBytes = file.getBytes();

    String pdfText;
    int pageCount;
    try (PDDocument doc = PDDocument.load(pdfBytes)) {
      pageCount = doc.getNumberOfPages();
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setSortByPosition(true);
      pdfText = stripper.getText(doc);
    } catch (IOException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR,
        "PDF 读取失败（可能已加密或损坏）：" + e.getMessage());
    }

    if (isCreditReport(pdfText, fileName)) {
      throw new BusinessException(CommonResultStatus.FAIL, "征信报告解析暂未开放，请上传银行流水 PDF");
    }

    String code = detectBankCode(bankCode, headerTextOf(pdfText), fileName);
    boolean needOcr = forceOcr || looksScanned(pdfText, pageCount);

    BankStatementDTO dto;
    String mode;
    if (needOcr) {
      JSONArray pages = ocrPages(pdfBytes);
      // OCR 词序更接近页面版式，用它重新识别一次银行，识别不到再沿用文本层结果
      String ocrCode = detectBankCode(null, joinOcrWords(pages), fileName);
      if (ocrCode != null) {
        code = ocrCode;
      }
      if (code == null) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "该 PDF 未包含银行名称，无法自动识别，请在上传时手动选择银行（ICBC/PSBC/GXRCB/ABCHX/GLGMCZYH/WECHAT）");
      }
      dto = parseOcrPages(code, pages, fileName);
      mode = "OCR";
      log.info("上传文件 {} 判定为扫描件，OCR 解析，银行={}", fileName, code);
    } else {
      if (code == null) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "该 PDF 未包含银行名称，无法自动识别，请在上传时手动选择银行（ICBC/PSBC/GXRCB/ABCHX/GLGMCZYH/WECHAT）");
      }
      dto = parseNativePdf(code, pdfBytes);
      mode = "NATIVE";
      log.info("上传文件 {} 判定为原生 PDF，银行={}", fileName, code);
    }

    // 户名、账号是流水单的必备标识，识别不到直接拒绝入库
    StatementHeader header = dto.getHeader();
    String accountNo = header == null ? null : header.getAccountNo();
    String accountHolder = header == null ? null : header.getAccountHolder();
    boolean lackAccountNo = isBlankField(accountNo, "账号", "卡号", "账号/户名", "账号／户名");
    boolean lackHolder = isBlankField(accountHolder, "户名", "户 名", "账号/户名", "账号／户名");
    if (lackAccountNo || lackHolder) {
      String missing = lackAccountNo && lackHolder ? "户名和账号" : (lackAccountNo ? "账号" : "户名");
      throw new BusinessException(CommonResultStatus.FAIL,
        "未能识别到" + missing + "，请到后台维护户名账号信息后重新上传");
    }

    List<tech.wetech.admin3.DTO.TransactionDTO> transactions = dto.getTransactions();
    if (transactions == null || transactions.isEmpty()) {
      throw new BusinessException(CommonResultStatus.FAIL,
        "未解析到任何交易明细，请确认文件版式，或尝试 forceOcr=true 强制 OCR");
    }

    // 入库前做一致性校验：余额勾稽 + 汇总对账，识别错漏在此暴露
    List<String> warnings = validateStatement(dto);

    boolean duplicated = bankStatementService.existsSameStatement(code, dto.getHeader());
    // 需在入库前判断：入库时若有重叠账期会直接合并进已有流水单
    boolean merged = !duplicated && bankStatementService.hasOverlappingStatement(code, dto.getHeader());
    String sourceKey = null;
    if (!duplicated && !merged) {
      StorageFileDTO stored = storageService.store(
        null, new java.io.ByteArrayInputStream(pdfBytes), file.getSize(), file.getContentType(), fileName);
      sourceKey = stored.getKey();
    }
    Long orgId = null;
    String orgName = null;
    UserinfoDTO currentUser = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    if (currentUser != null && currentUser.userId() != null) {
      orgId = userRepository.findOrgIdByUserId(currentUser.userId()).orElse(null);
      if (orgId != null) {
        orgName = userRepository.findOrgNameByUserId(currentUser.userId()).orElse(null);
      }
    }
    BankStatement saved = bankStatementService.importStatement(code, dto, fileName, sourceKey, orgId, orgName);

    StatementSummary summary = dto.getSummary();
    return new ImportResult(
      mode,
      code,
      header == null ? null : header.getBankName(),
      header == null ? null : header.getAccountNo(),
      header == null ? null : header.getAccountHolder(),
      header == null ? null : header.getStartDate(),
      header == null ? null : header.getEndDate(),
      saved.getId(),
      saved.getTransactionCount() == null ? transactions.size() : saved.getTransactionCount(),
      summary == null ? null : summary.getTotalIncome(),
      summary == null ? null : summary.getIncomeCount(),
      summary == null ? null : summary.getTotalExpense(),
      summary == null ? null : summary.getExpenseCount(),
      duplicated,
      merged,
      warnings
    );
  }

  /**
   * 判断明细是否为倒序（最新在前）。优先比首末交易日期，日期相同则比序号。
   */
  private boolean isDescending(List<TransactionDTO> list) {
    TransactionDTO first = null;
    TransactionDTO last = null;
    for (TransactionDTO tx : list) {
      if (first == null && tx.getTransDate() != null) {
        first = tx;
      }
      if (tx.getTransDate() != null) {
        last = tx;
      }
    }
    if (first == null || last == null) {
      first = list.get(0);
      last = list.get(list.size() - 1);
    }
    if (first.getTransDate() != null && last.getTransDate() != null
      && !first.getTransDate().equals(last.getTransDate())) {
      return first.getTransDate().isAfter(last.getTransDate());
    }
    Integer firstSeq = first.getSeqNo();
    Integer lastSeq = last.getSeqNo();
    return firstSeq != null && lastSeq != null && firstSeq > lastSeq;
  }

  /**
   * 判断字段在去掉标签前缀后是否为空，如 "账号：" 视为空，"账号：6228" 视为有值。
   */
  private boolean isBlankField(String value, String... labels) {
    if (value == null) {
      return true;
    }
    String v = value.trim();
    for (String label : labels) {
      if (v.startsWith(label)) {
        v = v.substring(label.length()).replaceFirst("^[\\s:：/／]+", "").trim();
      }
    }
    return v.isEmpty();
  }

  /**
   * 解析结果一致性校验。
   * <p>1）余额勾稽：正向流水「上一笔余额 + 本笔金额 = 本笔余额」，
   * 倒序流水（新→旧）「上一笔余额 − 上一笔金额 = 本笔余额」，断裂说明漏行或金额识别错误；
   * <p>2）汇总对账：明细累计收支与表头汇总值比对。
   *
   * @return 警告信息列表（空表示校验通过）
   */
  private List<String> validateStatement(BankStatementDTO dto) {
    List<String> warnings = new ArrayList<>();
    List<TransactionDTO> list = dto.getTransactions();
    if (list == null || list.isEmpty()) {
      return warnings;
    }
    BigDecimal tolerance = new BigDecimal("0.01");
    // 部分银行导出的明细是倒序（最新在前），勾稽公式要用反推
    boolean descending = isDescending(list);

    // ========== 1. 余额勾稽 ==========
    TransactionDTO prev = null;
    int broken = 0;
    for (TransactionDTO tx : list) {
      if (prev != null && prev.getBalance() != null && tx.getBalance() != null
        && tx.getAmount() != null && prev.getAmount() != null) {
        BigDecimal forward = prev.getBalance().add(tx.getAmount());
        BigDecimal backward = prev.getBalance().subtract(prev.getAmount());
        // 正向、倒序两种勾稽只要有一种成立就认为这一对是连续的，
        // 避免同一文件内日期并列或局部乱序造成误判
        boolean forwardOk = forward.subtract(tx.getBalance()).abs().compareTo(tolerance) <= 0;
        boolean backwardOk = backward.subtract(tx.getBalance()).abs().compareTo(tolerance) <= 0;
        if (!forwardOk && !backwardOk) {
          BigDecimal expected = descending ? backward : forward;
          broken++;
          if (broken <= 3) {
            warnings.add(String.format("第 %s 笔（%s）余额不连续：按上一笔推算应为 %s，实际 %s",
              tx.getSeqNo() == null ? "?" : tx.getSeqNo(),
              tx.getTransDate() == null ? "-" : tx.getTransDate(),
              expected.setScale(2, java.math.RoundingMode.HALF_UP),
              tx.getBalance().setScale(2, java.math.RoundingMode.HALF_UP)));
          }
        }
      }
      prev = tx;
    }
    if (broken > 3) {
      warnings.add("共 " + broken + " 处余额不连续，可能存在漏行、多行或金额识别错误");
    }

    // ========== 2. 汇总对账 ==========
    StatementSummary summary = dto.getSummary();
    if (summary != null) {
      BigDecimal income = BigDecimal.ZERO;
      BigDecimal expense = BigDecimal.ZERO;
      int mappedNeutral = 0;
      for (TransactionDTO tx : list) {
        if (tx.getAmount() == null) {
          continue;
        }
        // 仍有未映射的「其他」时不参与收支合计（direction 已按业务口径映射的行正常计入）
        if ("其他".equals(tx.getDirection())) {
          continue;
        }
        if ("其他".equals(tx.getRawDirection())) {
          mappedNeutral++;
        }
        if (tx.getAmount().signum() >= 0) {
          income = income.add(tx.getAmount());
        } else {
          expense = expense.add(tx.getAmount().abs());
        }
      }
      if (mappedNeutral > 0) {
        // 微信证明的「其他」不计收支，这里按业务口径映射为入/出，故合计与证明自带口径存在差额，属预期
        warnings.add("微信「其他」类 " + mappedNeutral
          + " 笔已按业务口径映射为入/出，收支合计按业务口径统计，与微信证明「不计收支」合计存在差额，属预期");
      }
      BigDecimal incomeDiff = summary.getTotalIncome() == null
        ? null : income.subtract(summary.getTotalIncome());
      BigDecimal expenseDiff = summary.getTotalExpense() == null
        ? null : expense.subtract(summary.getTotalExpense().abs());
      // 存在冲正/退款时，表头按净额统计而明细按逐笔统计，两边差额会同时出现且数值相等，此时不告警
      boolean reversalOffset = incomeDiff != null && expenseDiff != null
        && incomeDiff.signum() != 0
        && incomeDiff.subtract(expenseDiff).abs().compareTo(tolerance) <= 0;
      if (!reversalOffset && incomeDiff != null && incomeDiff.abs().compareTo(tolerance) > 0) {
        warnings.add("收入合计不一致：表头 " + summary.getTotalIncome() + "，明细累计 " + income);
      }
      if (!reversalOffset && expenseDiff != null && expenseDiff.abs().compareTo(tolerance) > 0) {
        warnings.add("支出合计不一致：表头 " + summary.getTotalExpense() + "，明细累计 " + expense);
      }
    }
    return warnings;
  }

  /**
   * 按银行分发 OCR 结果解析器。
   */
  private BankStatementDTO parseOcrPages(String bankCode, JSONArray pages, String fileName) {
    if ("ICBC".equals(bankCode)) {
      return IcbcOcrStatementParser.parse(pages);
    }
    return parserFacade.parse(pages, fileName, bankCode);
  }

  // ==================== 判断 PDF 类型 ====================

  /**
   * 是否为扫描件（无有效文本层）。
   */
  boolean looksScanned(String text, int pageCount) {
    String compact = text == null ? "" : text.replaceAll("\\s+", "");
    if (compact.length() < MIN_TEXT_CHARS) {
      return true;
    }
    int pages = Math.max(pageCount, 1);
    if (compact.length() / (double) pages < MIN_CHARS_PER_PAGE) {
      return true;
    }
    return !DATE.matcher(compact).find();
  }

  private boolean isCreditReport(String text, String fileName) {
    String all = (text == null ? "" : text) + "\n" + (fileName == null ? "" : fileName);
    return all.contains("个人信用报告") || all.contains("征信中心") || all.contains("信用报告");
  }

  // ==================== 银行识别 ====================

  /**
   * 只取表头区域文本用于银行识别，避免把「对方行名」里的银行名（如中国农业银行）误判为本行。
   */
  private String headerTextOf(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (String line : text.split("\\r?\\n")) {
      String t = line.trim();
      if (t.isEmpty()) {
        continue;
      }
      sb.append(t).append('\n');
      if (t.contains("交易时间") || t.contains("交易日期") || t.contains("序号")) {
        break;
      }
    }
    return sb.toString();
  }

  private String detectBankCode(String hint, String text, String fileName) {
    if (hint != null && !hint.isBlank()) {
      return hint.trim().toUpperCase(Locale.ROOT);
    }
    // 流水正文里会出现"工行/农行"等交易对手名称，先只看文档开头（标题区），避免误判
    String head = text == null ? "" : text.substring(0, Math.min(text.length(), 3000));
    String code = matchEarliest(head);
    if (code != null) return code;

    code = matchEarliest(text);
    if (code != null) return code;

    code = matchEarliest(fileName);
    if (code != null) return code;

    // 不做版式兜底：无法从名称识别时必须由用户手动选择银行，避免银行归属错乱
    return null;
  }

  /** 返回在文本中出现位置最靠前的银行编码 */
  private String matchEarliest(String haystack) {
    if (haystack == null || haystack.isEmpty()) return null;
    // 部分 PDF 标题字间带空格（如「桂 林 银 行」），匹配前先去掉空白
    String text = haystack.replaceAll("[\\s\\u00A0\\u3000]", "");
    String matched = null;
    int earliest = Integer.MAX_VALUE;
    for (Map.Entry<String, String> entry : BANK_KEYWORDS.entrySet()) {
      int idx = text.indexOf(entry.getKey());
      if (idx >= 0 && idx < earliest) {
        earliest = idx;
        matched = entry.getValue();
      }
    }
    return matched;
  }

  // ==================== 原生 PDF ====================

  private BankStatementDTO parseNativePdf(String bankCode, byte[] pdfBytes) throws IOException {
    File tmp = Files.createTempFile("statement-", ".pdf").toFile();
    try {
      Files.write(tmp.toPath(), pdfBytes);
      BankStatementDTO dto = switch (bankCode) {
        case "ICBC" -> IcbcNativePdfParser.parse(tmp);
        case "ABCHX" -> AbcHxNativePdfParser.parse(tmp);
        case "CCB" -> CcbNativePdfParser.parse(tmp);
        case "PSBC" -> PsbcNativePdfParser.parse(tmp);
        // 广西农商联合银行
        case "GXRCB" -> GxrcbNativePdfParser.parse(tmp);
        case "GLGMCZYH" -> NativePdfStatementParser.parse(tmp, "GLGMCZYH");
        case "GLYH" -> GlBankNativePdfParser.parse(tmp);
        case "WECHAT" -> WechatNativePdfParser.parse(tmp);
        default -> throw new BusinessException(CommonResultStatus.FAIL,
          "暂不支持该银行的原生 PDF 解析：" + bankCode + "，可尝试 forceOcr=true");
      };
      if ("GXRCB".equals(bankCode)) {
        dto.getHeader().setBankName("广西农商联合银行");
      }
      return dto;
    } finally {
      Files.deleteIfExists(tmp.toPath());
    }
  }

  // ==================== 扫描件 OCR ====================

  private JSONArray ocrPages(byte[] pdfBytes) throws IOException {
    AipOcr client = new AipOcr(BaiduOcr.APP_ID, BaiduOcr.API_KEY, BaiduOcr.SECRET_KEY);
    client.setConnectionTimeoutInMillis(10_000);
    client.setSocketTimeoutInMillis(120_000);

    JSONArray pages = new JSONArray();
    try (PDDocument doc = PDDocument.load(pdfBytes)) {
      PDFRenderer renderer = new PDFRenderer(doc);
      for (int i = 0; i < doc.getNumberOfPages(); i++) {
        byte[] png = renderPage(renderer, i);
        JSONObject res = client.table(png, new HashMap<>());
        if (res == null) {
          throw new BusinessException(CommonResultStatus.FAIL, "OCR 无响应（第 " + (i + 1) + " 页）");
        }
        if (res.has("error_code")) {
          throw new BusinessException(CommonResultStatus.FAIL,
            "OCR 识别失败（第 " + (i + 1) + " 页）：" + res.optString("error_msg"));
        }
        // 表格接口会裁掉表格上方的卡号/户名行，第 1 页额外跑一次通用 OCR
        if (i == 0) {
          res.put("general_words_result", generalOcr(client, png));
        }
        pages.put(res);
        log.info("OCR 第 {}/{} 页完成", i + 1, doc.getNumberOfPages());
      }
    }
    return pages;
  }

  private byte[] renderPage(PDFRenderer renderer, int index) throws IOException {
    BufferedImage image = renderer.renderImageWithDPI(index, OCR_DPI, ImageType.RGB);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(image, "png", out);
    return out.toByteArray();
  }

  private JSONArray generalOcr(AipOcr client, byte[] png) {
    JSONArray lines = new JSONArray();
    JSONObject res = client.basicAccurateGeneral(png, new HashMap<>());
    JSONArray words = res == null ? null : res.optJSONArray("words_result");
    if (words != null) {
      for (int i = 0; i < words.length(); i++) {
        lines.put(words.getJSONObject(i).optString("words"));
      }
    }
    return lines;
  }

  private BankStatementDTO parscdeOcrPages(String bankCode, JSONArray pages, String fileName) {
    if ("ICBC".equals(bankCode)) {
      return IcbcOcrStatementParser.parse(pages);
    }
    return parserFacade.parse(pages, fileName, bankCode);
  }

  private String joinOcrWords(JSONArray pages) {
    List<String> words = new ArrayList<>();
    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.optJSONObject(p);
      if (page == null) continue;
      JSONArray general = page.optJSONArray("general_words_result");
      if (general != null) {
        for (int i = 0; i < general.length(); i++) words.add(general.optString(i, ""));
      }
      JSONArray tables = page.optJSONArray("tables_result");
      if (tables == null) continue;
      for (int t = 0; t < tables.length(); t++) {
        JSONObject table = tables.optJSONObject(t);
        if (table == null) continue;
        collectWords(table.optJSONArray("header"), words);
        collectWords(table.optJSONArray("body"), words);
        collectWords(table.optJSONArray("footer"), words);
      }
    }
    return String.join("\n", words);
  }

  private void collectWords(JSONArray cells, List<String> words) {
    if (cells == null) return;
    for (int i = 0; i < cells.length(); i++) {
      JSONObject cell = cells.optJSONObject(i);
      if (cell == null) continue;
      String w = cell.optString("words", "");
      if (!w.isBlank()) words.add(w);
    }
  }

  /**
   * 入库结果。
   */
  public record ImportResult(
    String mode,
    String bankCode,
    String bankName,
    String accountNo,
    String accountHolder,
    LocalDate startDate,
    LocalDate endDate,
    Long statementId,
    int transactionCount,
    BigDecimal totalIncome,
    Integer incomeCount,
    BigDecimal totalExpense,
    Integer expenseCount,
    boolean duplicated,
    boolean merged,
    List<String> warnings
  ) {
  }
}
