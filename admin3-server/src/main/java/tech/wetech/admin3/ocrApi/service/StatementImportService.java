package tech.wetech.admin3.ocrApi.service;

import com.baidu.aip.ocr.AipOcr;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import tech.wetech.admin3.ocrApi.DocParserOcrAdapter;
import tech.wetech.admin3.ocrApi.DocParserOcrClient;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.parser.AbcHxNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.CcbNativePdfParser;
import tech.wetech.admin3.ocrApi.parser.ExcelStatementParser;
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
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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

  /** 扫描件 OCR 提供方：table=表格文字识别V2；doc-parser=文档解析（Unlimited-OCR） */
  @Value("${admin3.ocr.provider:table}")
  private String ocrProvider;

  /** doc-parser 原始解析结果存档目录；留空表示不存档 */
  @Value("${admin3.ocr.archive-dir:}")
  private String ocrArchiveDir;

  /** 同一份 PDF 已存档解析结果时是否直接复用（不再调用 OCR）；forceOcr=true 时跳过复用 */
  @Value("${admin3.ocr.reuse-cache:true}")
  private boolean ocrReuseCache;

  /** 原生 PDF 中小于此字符数视为扫描件 */
  private static final int MIN_TEXT_CHARS = 60;
  /** 原生 PDF 每页平均字符数下限 */
  private static final int MIN_CHARS_PER_PAGE = 40;
  /** 扫描件渲染 DPI */
  private static final int OCR_DPI = 300;

  private static final Pattern DATE = Pattern.compile("\\d{4}[-年]\\d{1,2}[-月]\\d{1,2}");

  /** 支持的图片扩展名（JDK 自带 ImageIO 可解码的常见格式） */
  private static final List<String> IMAGE_EXTENSIONS =
    List.of(".jpg", ".jpeg", ".png", ".bmp", ".tif", ".tiff");

  /** 银行编码 → 展示名称（Excel 表头未写银行全称时补全） */
  private static final Map<String, String> BANK_NAMES = Map.of(
    "ICBC", "中国工商银行",
    "PSBC", "中国邮政储蓄银行",
    "GXRCB", "广西农商联合银行",
    "ABCHX", "中国农业银行",
    "CCB", "中国建设银行",
    "GLGMCZYH", "桂林国民村镇银行",
    "GLYH", "桂林银行",
    "WECHAT", "微信支付"
  );

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
   * 解析上传的流水文件并入库。
   *
   * @param file       上传的 PDF、Excel(.xlsx) 或图片（jpg/jpeg/png/bmp/tif/tiff）
   * @param bankCode   银行编码（可选，传了就不再自动识别）
   * @param forceOcr   true=强制走 OCR（仅 PDF / 图片有效）
   */
  public ImportResult importStatement(MultipartFile file, String bankCode, boolean forceOcr) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "上传文件为空");
    }
    String fileName = file.getOriginalFilename();
    String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    boolean excel = lowerName.endsWith(".xlsx");
    boolean image = IMAGE_EXTENSIONS.stream().anyMatch(lowerName::endsWith);
    if (!excel && !image && !lowerName.endsWith(".pdf")) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR,
        "仅支持 PDF、Excel(.xlsx) 或图片（jpg/jpeg/png/bmp/tif/tiff）文件，当前文件：" + fileName);
    }
    if (excel) {
      return importExcel(file, bankCode);
    }

    byte[] fileBytes = file.getBytes();
    // 图片没有文本层：先归一化成单页 PDF，复用「扫描件 → OCR」链路；
    // 入库仍保存原始图片，OCR 提交时用 .pdf 文件名（文档解析按扩展名判断文件类型）
    byte[] pdfBytes = image ? imageToPdf(fileBytes) : fileBytes;
    String ocrFileName = image ? stripExtension(fileName) + ".pdf" : fileName;
    // 图片转 PDF 时 PDFBox 会写入运行时元数据，同一张图两次转换字节不一致，
    // 因此图片的解析结果缓存键取原始文件内容哈希
    String cacheKey = image ? sha256Hex(fileBytes) : null;

    String pdfText = "";
    int pageCount = 1;
    if (!image) {
      try (PDDocument doc = PDDocument.load(pdfBytes)) {
        pageCount = doc.getNumberOfPages();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        pdfText = stripper.getText(doc);
      } catch (IOException e) {
        throw new BusinessException(CommonResultStatus.PARAM_ERROR,
          "PDF 读取失败（可能已加密或损坏）：" + e.getMessage());
      }
    }

    if (isCreditReport(pdfText, fileName)) {
      throw new BusinessException(CommonResultStatus.FAIL, "征信报告解析暂未开放，请上传银行流水文件");
    }

    String code = detectBankCode(bankCode, headerTextOf(pdfText), fileName);
    boolean needOcr = image || forceOcr || looksScanned(pdfText, pageCount);

    BankStatementDTO dto;
    String mode;
    if (needOcr) {
      JSONArray pages = ocrPages(pdfBytes, ocrFileName, forceOcr, cacheKey);
      // OCR 词序更接近页面版式，用它重新识别一次银行，识别不到再沿用文本层结果
      String ocrCode = detectBankCode(null, joinOcrWords(pages), fileName);
      if (ocrCode != null) {
        code = ocrCode;
      }
      if (code == null) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "该文件未包含银行名称，无法自动识别，请在上传时手动选择银行（ICBC/PSBC/GXRCB/ABCHX/GLGMCZYH/WECHAT）");
      }
      dto = parseOcrPages(code, pages, fileName);
      mode = "OCR";
      log.info("上传文件 {} 判定为扫描件或图片，OCR 解析，银行={}", fileName, code);
    } else {
      if (code == null) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "该 PDF 未包含银行名称，无法自动识别，请在上传时手动选择银行（ICBC/PSBC/GXRCB/ABCHX/GLGMCZYH/WECHAT）");
      }
      dto = parseNativePdf(code, pdfBytes);
      mode = "NATIVE";
      log.info("上传文件 {} 判定为原生 PDF，银行={}", fileName, code);
    }

    // 入库保存用户上传的原始文件（图片存图片，PDF 存 PDF）
    return persist(mode, code, dto, fileBytes, file);
  }

  /**
   * 解析上传的 Excel(.xlsx) 流水并入库：
   * 自动识别表头，表头信息区的户名/账号/账期沿用与 PDF 相同的校验规则。
   */
  private ImportResult importExcel(MultipartFile file, String bankCode) throws IOException {
    String fileName = file.getOriginalFilename();
    byte[] bytes = file.getBytes();
    File tmp = Files.createTempFile("statement-", ".xlsx").toFile();
    BankStatementDTO dto;
    try {
      Files.write(tmp.toPath(), bytes);
      dto = ExcelStatementParser.parse(tmp, fileName);
    } finally {
      Files.deleteIfExists(tmp.toPath());
    }

    String code = detectBankCode(bankCode, dto.getRawOcrText(), fileName);
    if (code == null) {
      throw new BusinessException(CommonResultStatus.FAIL,
        "未能从 Excel 中识别银行名称，请在上传时手动选择银行（ICBC/PSBC/GXRCB/ABCHX/CCB/GLGMCZYH/GLYH/WECHAT）");
    }
    StatementHeader header = dto.getHeader();
    if (header.getBankName() == null || header.getBankName().isBlank()) {
      header.setBankName(BANK_NAMES.getOrDefault(code, code));
    }
    log.info("上传文件 {} 为 Excel，银行={}", fileName, code);
    return persist("EXCEL", code, dto, bytes, file);
  }

  /**
   * 校验并入库，PDF 与 Excel 共用，保证两条链路的判重、合并、校验口径一致。
   */
  private ImportResult persist(String mode, String code, BankStatementDTO dto,
                               byte[] fileBytes, MultipartFile file) {
    String fileName = file.getOriginalFilename();
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

    List<TransactionDTO> transactions = dto.getTransactions();
    if (transactions == null || transactions.isEmpty()) {
      throw new BusinessException(CommonResultStatus.FAIL,
        "未解析到任何交易明细，请确认文件版式，或尝试 forceOcr=true 强制 OCR");
    }

    // 入库前做一致性校验：余额勾稽 + 汇总对账，识别错漏在此暴露
    List<String> warnings = validateStatement(dto);

    // 同账号同账期可能是同一份流水的另一页：按交易指纹合并，而不是判重跳过
    Optional<BankStatement> sameStatement = bankStatementService.findSameStatement(code, dto.getHeader());
    int beforeCount = sameStatement.map(s -> s.getTransactionCount() == null ? 0 : s.getTransactionCount())
      .orElse(0);
    boolean overlapping = sameStatement.isEmpty()
      && bankStatementService.hasOverlappingStatement(code, dto.getHeader());
    String sourceKey = null;
    if (sameStatement.isEmpty() && !overlapping) {
      StorageFileDTO stored = storageService.store(
        null, new java.io.ByteArrayInputStream(fileBytes), file.getSize(), file.getContentType(), fileName);
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
    int afterCount = saved.getTransactionCount() == null ? transactions.size() : saved.getTransactionCount();
    int added = Math.max(0, afterCount - beforeCount);
    // 同一页重复上传：无新增明细；不同页：合并成功并新增明细
    boolean duplicated = sameStatement.isPresent() && added == 0;
    boolean merged = overlapping || (sameStatement.isPresent() && added > 0);

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

  /**
   * 图片转单页 A4 PDF：按比例适配页面，图片像素不被裁剪。
   *
   * <p>固定文档创建/修改时间，保证同一张图片每次转换出的 PDF 字节完全一致，
   * 这样「按内容哈希复用解析结果」的缓存在图片上传时也能命中。
   */
  private static byte[] imageToPdf(byte[] imageBytes) throws IOException {
    BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
    if (source == null) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR,
        "图片无法识别，请改用 JPG / PNG / BMP / TIF 格式");
    }
    BufferedImage rgb = toRgb(source);
    try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Calendar fixed = Calendar.getInstance();
      fixed.setTimeInMillis(0L);
      doc.getDocumentInformation().setCreationDate(fixed);
      doc.getDocumentInformation().setModificationDate(fixed);

      boolean landscape = rgb.getWidth() > rgb.getHeight();
      PDRectangle size = landscape
        ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
        : new PDRectangle(PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
      PDPage page = new PDPage(size);
      doc.addPage(page);

      float scale = Math.min(size.getWidth() / rgb.getWidth(), size.getHeight() / rgb.getHeight());
      float w = rgb.getWidth() * scale;
      float h = rgb.getHeight() * scale;
      float x = (size.getWidth() - w) / 2;
      float y = (size.getHeight() - h) / 2;

      // 0.92 质量：OCR 足够清晰，同时避免高分辨率扫描件生成的 PDF 过大
      PDImageXObject pdImage = JPEGFactory.createFromImage(doc, rgb, 0.92f);
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.drawImage(pdImage, x, y, w, h);
      }
      doc.save(out);
      return out.toByteArray();
    }
  }

  /** 统一转成 RGB，透明背景铺白，避免 PNG 透明区域在 JPEG 中变黑影响 OCR。 */
  private static BufferedImage toRgb(BufferedImage src) {
    if (src.getType() == BufferedImage.TYPE_INT_RGB && !src.getColorModel().hasAlpha()) {
      return src;
    }
    BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = rgb.createGraphics();
    g.setColor(Color.WHITE);
    g.fillRect(0, 0, src.getWidth(), src.getHeight());
    g.drawImage(src, 0, 0, null);
    g.dispose();
    return rgb;
  }

  private static String stripExtension(String name) {
    if (name == null || name.isBlank()) return "statement";
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  /**
   * 扫描件识别入口：按配置选择「文档解析（Unlimited-OCR）」或「表格文字识别V2」。
   * 两条链路输出同一种页面 JSON，下游解析器无需感知。
   */
  private JSONArray ocrPages(byte[] pdfBytes, String fileName, boolean forceOcr, String cacheKey) throws IOException {
    if ("doc-parser".equalsIgnoreCase(ocrProvider)) {
      return ocrPagesByDocParser(pdfBytes, fileName, forceOcr, cacheKey);
    }
    return ocrPagesByTable(pdfBytes);
  }

  /**
   * 文档解析（Unlimited-OCR）：整份文件上传，异步任务返回结构化结果后再转成页面 JSON。
   *
   * <p>同一份文件（按内容哈希识别；图片取原始文件哈希）已有存档结果时直接复用，
   * 跳过远端 OCR 调用；存档不存在、不可读或 forceOcr=true 时才重新识别。
   */
  private JSONArray ocrPagesByDocParser(byte[] pdfBytes, String fileName, boolean forceOcr, String cacheKey) {
    String hash = cacheKey != null ? cacheKey : sha256Hex(pdfBytes);
    JSONObject parsed = forceOcr ? null : readCachedResult(hash);
    if (parsed != null) {
      log.info("复用本地文档解析结果，跳过 OCR 调用：{}（{}）", fileName, hash);
    } else {
      parsed = DocParserOcrClient.parse(pdfBytes, fileName);
      archiveRawResult(fileName, parsed, hash);
    }
    JSONArray pages = DocParserOcrAdapter.toLegacyPages(parsed);
    int tables = 0;
    for (int i = 0; i < pages.length(); i++) {
      tables += pages.getJSONObject(i).getJSONArray("tables_result").length();
    }
    log.info("文档解析完成：{} 页，{} 张表格", pages.length(), tables);
    if (tables == 0) {
      log.warn("文档解析未识别到表格，后续解析可能失败，请检查 PDF 版式");
    }
    return pages;
  }

  /**
   * 读取按内容哈希缓存的解析结果；未开启复用、没有存档或存档不可用时返回 null。
   */
  private JSONObject readCachedResult(String hash) {
    if (!ocrReuseCache || ocrArchiveDir == null || ocrArchiveDir.isBlank()) {
      return null;
    }
    Path file = cacheDir().resolve(hash + ".json");
    if (!Files.isReadable(file)) {
      return null;
    }
    try {
      return new JSONObject(Files.readString(file, StandardCharsets.UTF_8));
    } catch (Exception e) {
      log.warn("本地文档解析结果不可用，将重新调用 OCR：{}（{}）", e.getMessage(), file);
      return null;
    }
  }

  /**
   * 存档 doc-parser 的原始解析结果：一份带时间戳的历史文件，一份按内容哈希命名的缓存文件。
   * 写入失败不影响导入。
   */
  private void archiveRawResult(String fileName, JSONObject parsed, String hash) {
    if (ocrArchiveDir == null || ocrArchiveDir.isBlank() || parsed == null) {
      return;
    }
    try {
      Path dir = Path.of(ocrArchiveDir);
      Files.createDirectories(dir);
      String base = fileName == null || fileName.isBlank() ? "statement" : fileName;
      base = base.replaceAll("(?i)\\.pdf$", "").replaceAll("[\\\\/:*?\"<>|\\s]", "_");
      String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
      Path history = dir.resolve(base + "-" + stamp + ".json");
      Files.writeString(history, parsed.toString(2), StandardCharsets.UTF_8);

      Path cache = cacheDir().resolve(hash + ".json");
      Files.createDirectories(cache.getParent());
      Files.writeString(cache, parsed.toString(2), StandardCharsets.UTF_8);
      log.info("文档解析原始结果已存档：{}；缓存：{}", history.toAbsolutePath(), cache.toAbsolutePath());
    } catch (Exception e) {
      log.warn("文档解析原始结果存档失败：{}", e.getMessage());
    }
  }

  private Path cacheDir() {
    return Path.of(ocrArchiveDir, "cache");
  }

  /** 文件内容 SHA-256，用作解析结果缓存键（文件名可重复，内容不会） */
  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
    }
  }

  /**
   * 表格文字识别V2：逐页渲染成图片再识别。
   */
  private JSONArray ocrPagesByTable(byte[] pdfBytes) throws IOException {
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
