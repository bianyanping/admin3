package tech.wetech.admin3.ocrApi.parser;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 银行流水 Excel(.xlsx) 解析器。
 *
 * <p>自动识别表头：逐行（含相邻两行合并的表头）用别名词典匹配列名，
 * 命中「日期 + 金额（或收入/支出分列）」且命中数最多的行判定为表头行，
 * 表头以上的行作为表头信息区，按「户名/账号/账期」等标签提取。
 *
 * <p>识别不到表头、缺少金额或日期列时直接报错，不做猜测。
 * 解析结果复用 PDF 链路的 {@link BankStatementDTO}，后续校验与入库逻辑完全一致。
 */
public final class ExcelStatementParser {

  private static final Logger LOG = LoggerFactory.getLogger(ExcelStatementParser.class);

  /** 表头字段名 */
  private static final String F_DATE = "date";
  private static final String F_DATETIME = "datetime";
  private static final String F_TIME = "time";
  private static final String F_AMOUNT = "amount";
  private static final String F_INCOME = "income";
  private static final String F_EXPENSE = "expense";
  private static final String F_BALANCE = "balance";
  private static final String F_DIRECTION = "direction";
  private static final String F_CP_NAME = "cpName";
  private static final String F_CP_ACCOUNT = "cpAccount";
  private static final String F_SUMMARY = "summary";
  private static final String F_PURPOSE = "purpose";
  private static final String F_POSTSCRIPT = "postscript";
  private static final String F_REMARK = "remark";
  private static final String F_CHANNEL = "channel";
  private static final String F_SEQ = "seq";

  /** 归一化后的表头文字 → 字段名 */
  private static final Map<String, String> ALIASES = new LinkedHashMap<>();

  static {
    put(F_DATE, "交易日期", "记账日期", "入账日期", "发生日期", "交易日", "日期", "交易发生日期", "记账日", "入账日", "会计日期");
    put(F_DATETIME, "交易日期时间", "交易日期及时间", "记账时间", "入账时间", "发生时间", "交易发生时间", "日期时间", "交易日期和时间");
    put(F_TIME, "交易时间", "交易时刻", "时间", "交易时分", "时分秒");
    put(F_AMOUNT, "交易金额", "发生额", "金额", "交易发生额", "交易额", "本次交易金额", "本次发生额", "收支金额");
    put(F_INCOME, "收入金额", "贷方金额", "收入", "存入金额", "进账金额", "贷方发生额", "收款金额", "入账金额", "收入额");
    put(F_EXPENSE, "支出金额", "借方金额", "支出", "支取金额", "出账金额", "借方发生额", "付款金额", "出账额", "支出额");
    put(F_BALANCE, "账户余额", "余额", "交易后余额", "本次余额", "当前余额", "结余", "交易后账户余额", "可用余额");
    put(F_DIRECTION, "借贷标志", "收付标志", "交易方向", "借贷", "收/支", "收支标志", "收付方向", "借贷方向", "收付类型", "收支类型");
    put(F_CP_NAME, "对方户名", "交易对方", "对方账户名称", "对方名称", "对手户名", "交易对手", "对方单位名称",
      "对手方名称", "对方客户名称", "收付款人", "对方姓名", "对方");
    put(F_CP_ACCOUNT, "对方账号", "对方账户", "对方卡号", "对方账户账号", "对手账号", "对方账号/卡号", "对方账户号");
    put(F_SUMMARY, "摘要", "交易摘要", "摘要说明");
    put(F_PURPOSE, "用途", "交易用途", "资金用途");
    put(F_POSTSCRIPT, "附言", "交易附言");
    put(F_REMARK, "备注", "说明", "其他说明");
    put(F_CHANNEL, "交易渠道", "渠道", "交易方式", "业务渠道", "交易类型");
    put(F_SEQ, "序号", "交易序号", "流水号", "明细序号", "编号", "交易流水号");
  }

  private static void put(String field, String... aliases) {
    for (String alias : aliases) {
      ALIASES.put(alias, field);
    }
  }

  /** 表头区信息标签 */
  private static final String[] LABEL_HOLDER = {"账户名称", "账户户名", "户名", "客户名称", "持卡人姓名", "持卡人", "姓名"};
  private static final String[] LABEL_ACCOUNT = {"账号/户名", "账号", "卡号", "账户号码", "账户号", "银行账号"};
  private static final String[] LABEL_BANK = {"开户行", "开户银行", "银行名称"};
  private static final String[] LABEL_CURRENCY = {"币种"};

  private static final Pattern DATE_RANGE = Pattern.compile(
    "(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)\\s*(?:至|到|~|～|——|—)\\s*(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)");
  /** 一行内可能连续出现多个「标签：值」，如「户名：王五  账号：6222…」 */
  private static final Pattern INLINE_META = Pattern.compile(
    "(账户名称|账户户名|户名|客户名称|持卡人姓名|持卡人|姓名|账号/户名|账号|卡号|账户号码|账户号|银行账号|开户行|开户银行|银行名称|币种)"
      + "\\s*[：:]\\s*([^：:]*)"
      + "(?=\\s*(?:账户名称|账户户名|户名|客户名称|持卡人姓名|持卡人|姓名|账号/户名|账号|卡号|账户号码|账户号|银行账号|开户行|开户银行|银行名称|币种)\\s*[：:]|\\s*$)");
  private static final Pattern DATE_TEXT = Pattern.compile(
    "(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?:[\\sT]*(\\d{1,2})[:：时](\\d{1,2})(?:[:：分](\\d{1,2}))?)?");
  private static final Pattern COMPACT_DATE = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
  private static final Pattern TIME_TEXT = Pattern.compile("(\\d{1,2})[:：时](\\d{2})(?:[:：分](\\d{2}))?");
  private static final Pattern NUMBER_TEXT = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

  /** 表头行最多向上扫描的行数 */
  private static final int MAX_HEADER_SCAN_ROWS = 40;
  /** 判定表头需要命中的最少列数 */
  private static final int MIN_HEADER_FIELDS = 3;

  private final DataFormatter formatter = new DataFormatter(Locale.CHINA);
  private final Sheet sheet;
  private final Map<Integer, List<CellRangeAddress>> mergedByRow = new HashMap<>();

  private ExcelStatementParser(Sheet sheet) {
    this.sheet = sheet;
    for (CellRangeAddress region : sheet.getMergedRegions()) {
      for (int r = region.getFirstRow(); r <= region.getLastRow(); r++) {
        mergedByRow.computeIfAbsent(r, k -> new ArrayList<>()).add(region);
      }
    }
  }

  /**
   * 解析 Excel 流水的第一个工作表。
   *
   * @param xlsx     上传的 .xlsx 文件
   * @param fileName 原始文件名，用于日志与银行识别
   */
  public static BankStatementDTO parse(File xlsx, String fileName) throws IOException {
    try (XSSFWorkbook workbook = new XSSFWorkbook(xlsx)) {
      Sheet target = null;
      HeaderMatch header = null;
      for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
        Sheet sheet = workbook.getSheetAt(i);
        HeaderMatch match = detectHeader(sheet);
        if (match == null || !hasDataRow(sheet, match)) {
          continue;
        }
        if (target != null) {
          throw new BusinessException(CommonResultStatus.FAIL,
            "该 Excel 包含多个流水工作表，请拆分后分别导入");
        }
        target = sheet;
        header = match;
      }
      if (target == null) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "未识别到流水表头，请确认文件包含「交易日期 + 交易金额」等列，或另存为规范的明细表后重试");
      }
      LOG.info("Excel {} 表头位于第 {} 行，识别列 {}", fileName, header.startRow + 1, header.fields);
      return new ExcelStatementParser(target).read(header, fileName);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      throw new BusinessException(CommonResultStatus.FAIL, "Excel 读取失败（文件可能已损坏）：" + e.getMessage());
    }
  }

  // ==================== 表头识别 ====================

  private static final class HeaderMatch {
    int startRow;
    int rowCount;
    int score;
    final Map<String, Integer> fields = new LinkedHashMap<>();
  }

  private static HeaderMatch detectHeader(Sheet sheet) {
    int last = Math.min(sheet.getLastRowNum(), MAX_HEADER_SCAN_ROWS);
    HeaderMatch best = null;
    for (int r = 0; r <= last; r++) {
      HeaderMatch single = matchHeader(sheet, r, 1);
      best = better(best, single);
      if (r < last) {
        best = better(best, matchHeader(sheet, r, 2));
      }
    }
    return best;
  }

  /** 分数更高优先；同分时优先单行表头（更靠前） */
  private static HeaderMatch better(HeaderMatch current, HeaderMatch candidate) {
    if (candidate == null) {
      return current;
    }
    if (current == null) {
      return candidate;
    }
    if (candidate.score != current.score) {
      return candidate.score > current.score ? candidate : current;
    }
    // 同分时优先单行表头，避免把表头上方的户名/账号行并进表头
    if (candidate.rowCount != current.rowCount) {
      return candidate.rowCount < current.rowCount ? candidate : current;
    }
    return current;
  }

  private static HeaderMatch matchHeader(Sheet sheet, int startRow, int rowCount) {
    Row first = sheet.getRow(startRow);
    Row second = rowCount > 1 ? sheet.getRow(startRow + 1) : null;
    if (first == null && second == null) {
      return null;
    }
    int lastCol = Math.max(first == null ? 0 : first.getLastCellNum(), second == null ? 0 : second.getLastCellNum());
    Map<String, Integer> fields = new LinkedHashMap<>();
    for (int c = 0; c < lastCol; c++) {
      String field = fieldOf(joinCellText(sheet, startRow, c, second == null ? null : sheet.getRow(startRow + 1)));
      if (field != null && !fields.containsKey(field)) {
        fields.put(field, c);
      }
    }
    // 只有「交易时间」一列且没有日期列时，该列通常是完整日期时间
    if (!fields.containsKey(F_DATE) && !fields.containsKey(F_DATETIME) && fields.containsKey(F_TIME)) {
      Integer col = fields.remove(F_TIME);
      fields.put(F_DATETIME, col);
    }
    boolean hasDate = fields.containsKey(F_DATE) || fields.containsKey(F_DATETIME);
    boolean hasAmount = fields.containsKey(F_AMOUNT)
      || fields.containsKey(F_INCOME) || fields.containsKey(F_EXPENSE);
    if (!hasDate || !hasAmount || fields.size() < MIN_HEADER_FIELDS) {
      return null;
    }
    HeaderMatch match = new HeaderMatch();
    match.startRow = startRow;
    match.rowCount = rowCount;
    match.score = fields.size();
    match.fields.putAll(fields);
    return match;
  }

  /**
   * 两行表头按列合并：优先取第二行（如「金额」跨列 + 「收入金额/支出金额」），其次第一行，
   * 最后才拼接，避免单行纵向合并的表头丢列。
   */
  private static String joinCellText(Sheet sheet, int rowIndex, int col, Row second) {
    Row first = sheet.getRow(rowIndex);
    String a = rawCellText(cellAt(first, col));
    String b = rawCellText(cellAt(second, col));
    if (second == null) {
      return a;
    }
    if (!b.isEmpty() && aliasOf(b) != null) {
      return b;
    }
    if (!a.isEmpty() && aliasOf(a) != null) {
      return a;
    }
    return a.isEmpty() ? b : (b.isEmpty() ? a : a + b);
  }

  /** 归一化表头文字并匹配字段 */
  private static String fieldOf(String raw) {
    String normalized = normalizeHeader(raw);
    String field = ALIASES.get(normalized);
    if (field != null) {
      return field;
    }
    // 带括号说明的列名：「交易金额(收入)」「金额（支出）」
    Matcher m = Pattern.compile("^(.+?)\\(([^)]*)\\)$").matcher(normalized);
    if (m.matches()) {
      String inner = m.group(2);
      if (inner.contains("收入") || inner.contains("贷") || inner.contains("存入")) {
        return F_INCOME;
      }
      if (inner.contains("支出") || inner.contains("借") || inner.contains("支取")) {
        return F_EXPENSE;
      }
      return ALIASES.get(m.group(1));
    }
    return null;
  }

  private static String aliasOf(String raw) {
    return ALIASES.get(normalizeHeader(raw));
  }

  private static String normalizeHeader(String raw) {
    if (raw == null) {
      return "";
    }
    String s = raw.replaceAll("[\\s\\u00A0\\u3000\\uFEFF]", "")
      .replace('（', '(').replace('）', ')')
      .replace('：', ':').replace('／', '/')
      .replace("＊", "").replace("*", "");
    if (s.endsWith(":") || s.endsWith("(")) {
      s = s.substring(0, s.length() - 1);
    }
    // 去掉单位说明：(元)(人民币)(万元)
    String noUnit = s.replaceAll("\\((元|人民币|元整|万元|分|角)\\)$", "");
    return noUnit;
  }

  // ==================== 解析主体 ====================

  private BankStatementDTO read(HeaderMatch header, String fileName) {
    Meta meta = new Meta();
    meta.rawHeaderText = readHeaderArea(header.startRow, meta);

    int dataStart = header.startRow + header.rowCount;
    List<TransactionDTO> transactions = new ArrayList<>();
    int lastRow = sheet.getLastRowNum();
    for (int r = dataStart; r <= lastRow; r++) {
      Row row = sheet.getRow(r);
      if (row == null) {
        continue;
      }
      if (isFooterRow(row)) {
        break;
      }
      TransactionDTO tx = readTransaction(row, header.fields);
      if (tx != null) {
        transactions.add(tx);
      }
    }
    if (transactions.isEmpty()) {
      throw new BusinessException(CommonResultStatus.FAIL,
        "未从 Excel 中解析到任何交易明细，请确认表头下存在有效数据行");
    }
    for (int i = 0; i < transactions.size(); i++) {
      if (transactions.get(i).getSeqNo() == null) {
        transactions.get(i).setSeqNo(i + 1);
      }
    }

    StatementHeader statementHeader = new StatementHeader();
    statementHeader.setAccountHolder(meta.holder);
    statementHeader.setAccountNo(meta.accountNo);
    statementHeader.setBankName(meta.bankName);
    statementHeader.setCurrency(meta.currency == null ? "人民币" : meta.currency);
    statementHeader.setStartDate(meta.startDate != null ? meta.startDate : minDate(transactions));
    statementHeader.setEndDate(meta.endDate != null ? meta.endDate : maxDate(transactions));
    if (statementHeader.getStartDate() == null || statementHeader.getEndDate() == null) {
      throw new BusinessException(CommonResultStatus.FAIL,
        "未能从 Excel 中识别账期（交易日期或账期说明），请补充后重新上传");
    }

    BankStatementDTO dto = new BankStatementDTO();
    dto.setHeader(statementHeader);
    dto.setTransactions(transactions);
    dto.setSummary(buildSummary(transactions));
    dto.setRawOcrText(meta.rawHeaderText);
    return dto;
  }

  private TransactionDTO readTransaction(Row row, Map<String, Integer> fields) {
    LocalDateTime dateTime = dateTimeOf(mergedCell(row, fields.get(F_DATETIME)));
    LocalDate date = dateTime != null ? dateTime.toLocalDate() : dateOf(mergedCell(row, fields.get(F_DATE)));
    String time = timeOf(mergedCell(row, fields.get(F_TIME)));
    if (time == null && dateTime != null && !dateTime.toLocalTime().equals(LocalTime.MIDNIGHT)) {
      time = String.format("%02d:%02d:%02d", dateTime.getHour(), dateTime.getMinute(), dateTime.getSecond());
    }
    if (date == null) {
      return null;
    }

    String direction = normalizeDirection(textOf(mergedCell(row, fields.get(F_DIRECTION))));
    BigDecimal amount = null;
    boolean explicit = false;

    if (fields.containsKey(F_AMOUNT)) {
      amount = amountOf(mergedCell(row, fields.get(F_AMOUNT)));
      if (amount != null) {
        if (direction != null) {
          if ("出".equals(direction) && amount.signum() > 0) {
            amount = amount.negate();
          } else if ("入".equals(direction) && amount.signum() < 0) {
            amount = amount.abs();
          }
        } else {
          direction = amount.signum() < 0 ? "出" : (amount.signum() > 0 ? "入" : null);
          explicit = true;
        }
      }
    }
    if (amount == null && (fields.containsKey(F_INCOME) || fields.containsKey(F_EXPENSE))) {
      BigDecimal income = amountOf(mergedCell(row, fields.get(F_INCOME)));
      BigDecimal expense = amountOf(mergedCell(row, fields.get(F_EXPENSE)));
      boolean hasIncome = income != null && income.signum() != 0;
      boolean hasExpense = expense != null && expense.signum() != 0;
      if (hasIncome) {
        amount = income.abs();
        direction = "入";
      } else if (hasExpense) {
        amount = expense.abs().negate();
        direction = "出";
      }
      explicit = false;
    }
    if (amount == null) {
      return null;
    }

    TransactionDTO tx = new TransactionDTO();
    tx.setTransDate(date);
    tx.setTransTime(time);
    if (dateTime != null) {
      tx.setTransDateTime(dateTime);
    } else if (time != null) {
      tx.setTransDateTime(LocalDateTime.of(date, LocalTime.parse(normalizeTime(time))));
    }
    tx.setAmount(amount.setScale(2, RoundingMode.HALF_UP));
    tx.setBalance(scale(amountOf(mergedCell(row, fields.get(F_BALANCE)))));
    tx.setDirection(direction);
    tx.setAmountSignExplicit(explicit);
    tx.setCounterpartyName(textOf(mergedCell(row, fields.get(F_CP_NAME))));
    tx.setCounterpartyAccount(textOf(mergedCell(row, fields.get(F_CP_ACCOUNT))));
    tx.setSummary(textOf(mergedCell(row, fields.get(F_SUMMARY))));
    tx.setPurpose(textOf(mergedCell(row, fields.get(F_PURPOSE))));
    tx.setPostscript(textOf(mergedCell(row, fields.get(F_POSTSCRIPT))));
    tx.setRemark(textOf(mergedCell(row, fields.get(F_REMARK))));
    tx.setChannel(textOf(mergedCell(row, fields.get(F_CHANNEL))));
    tx.setSeqNo(intOf(mergedCell(row, fields.get(F_SEQ))));
    return tx;
  }

  /** 表头区（表头行以上）提取户名/账号/账期/银行 */
  private String readHeaderArea(int headerRow, Meta meta) {
    StringBuilder raw = new StringBuilder();
    for (int r = 0; r < headerRow; r++) {
      Row row = sheet.getRow(r);
      if (row == null) {
        continue;
      }
      short first = row.getFirstCellNum();
      short last = row.getLastCellNum();
      for (int c = Math.max(first, 0); c < last; c++) {
        String text = textOf(mergedCell(row, c));
        if (text.isEmpty()) {
          continue;
        }
        raw.append(text).append(' ');
        readMetaText(row, text, c, meta);
      }
      raw.append('\n');
    }
    return raw.toString().trim();
  }

  private void readMetaText(Row row, String text, int col, Meta meta) {
    Matcher range = DATE_RANGE.matcher(text);
    if (range.find()) {
      meta.startDate = dateOf(range.group(1));
      meta.endDate = dateOf(range.group(2));
    }

    Matcher labeled = INLINE_META.matcher(text);
    boolean matched = false;
    while (labeled.find()) {
      if (labeled.group(2).isBlank()) {
        continue;
      }
      applyMeta(labeled.group(1), labeled.group(2), meta);
      matched = true;
    }
    if (matched) {
      return;
    }
    String key = matchLabel(text);
    if (key == null) {
      return;
    }
    String value = nextNonEmptyText(row, col + 1);
    if (value == null || matchLabel(value) != null) {
      return;
    }
    applyMetaValue(key, value, false, meta);
  }

  private String matchLabel(String text) {
    String value = text.replaceAll("[\\s\\u00A0\\u3000]", "").replace('：', ':');
    if (value.endsWith(":")) {
      value = value.substring(0, value.length() - 1);
    }
    for (String label : LABEL_HOLDER) {
      if (value.equals(label)) {
        return "holder";
      }
    }
    for (String label : LABEL_ACCOUNT) {
      if (value.equals(label)) {
        return "account";
      }
    }
    for (String label : LABEL_BANK) {
      if (value.equals(label)) {
        return "bank";
      }
    }
    for (String label : LABEL_CURRENCY) {
      if (value.equals(label)) {
        return "currency";
      }
    }
    return null;
  }

  private void applyMeta(String label, String value, Meta meta) {
    String cleanLabel = label.replaceAll("[\\s\\u00A0\\u3000]", "");
    // 兼容「账号/户名」合并标签：值形如「6228xxx 张三」
    boolean combined = cleanLabel.contains("账号") && cleanLabel.contains("户名");
    String key = matchLabel(label);
    if (key == null && !combined) {
      return;
    }
    applyMetaValue(key == null ? "account" : key, value, combined, meta);
  }

  private void applyMetaValue(String key, String value, boolean combined, Meta meta) {
    String text = value.trim().replaceAll("^[：:\\s]+", "")
      .replaceAll("[\\s\\u00A0\\u3000]+$", "");
    if (text.isEmpty()) {
      return;
    }
    switch (key) {
      case "holder" -> meta.holder = text;
      case "account" -> {
        if (combined) {
          String[] parts = text.split("[\\s\\u00A0\\u3000]+", 2);
          meta.accountNo = parts[0];
          if (parts.length > 1 && meta.holder == null) {
            meta.holder = parts[1].trim();
          }
        } else {
          meta.accountNo = text.replaceAll("[\\s\\u00A0\\u3000]", "");
        }
      }
      case "bank" -> meta.bankName = text;
      case "currency" -> meta.currency = text;
      default -> {
      }
    }
  }

  private String nextNonEmptyText(Row row, int fromCol) {
    short last = row.getLastCellNum();
    for (int c = fromCol; c < last; c++) {
      String text = textOf(mergedCell(row, c));
      if (!text.isEmpty()) {
        return text;
      }
    }
    return null;
  }

  private boolean isFooterRow(Row row) {
    short first = row.getFirstCellNum();
    short last = row.getLastCellNum();
    for (int c = Math.max(first, 0); c < last; c++) {
      String text = textOf(mergedCell(row, c));
      if (text.isEmpty()) {
        continue;
      }
      String normalized = text.replaceAll("[\\s\\u00A0\\u3000]", "");
      return normalized.startsWith("合计") || normalized.startsWith("总计")
        || normalized.startsWith("小计") || normalized.startsWith("本页合计")
        || normalized.startsWith("本页小计") || normalized.startsWith("累计")
        || normalized.startsWith("总笔数") || normalized.startsWith("制表")
        || normalized.startsWith("打印时间") || normalized.startsWith("说明");
    }
    return false;
  }

  private static boolean hasDataRow(Sheet sheet, HeaderMatch header) {
    int dataStart = header.startRow + header.rowCount;
    int last = Math.min(sheet.getLastRowNum(), dataStart + 10);
    for (int r = dataStart; r <= last; r++) {
      Row row = sheet.getRow(r);
      if (row == null) {
        continue;
      }
      int nonEmpty = 0;
      for (int c = 0; c < row.getLastCellNum(); c++) {
        if (!rawCellText(cellAt(row, c)).isEmpty()) {
          nonEmpty++;
        }
      }
      if (nonEmpty >= 3) {
        return true;
      }
    }
    return false;
  }

  private static StatementSummary buildSummary(List<TransactionDTO> transactions) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expense = BigDecimal.ZERO;
    int incomeCount = 0;
    int expenseCount = 0;
    for (TransactionDTO tx : transactions) {
      if (tx.getAmount() == null || "其他".equals(tx.getDirection())) {
        continue;
      }
      if (tx.getAmount().signum() >= 0) {
        income = income.add(tx.getAmount());
        incomeCount++;
      } else {
        expense = expense.add(tx.getAmount().abs());
        expenseCount++;
      }
    }
    StatementSummary summary = new StatementSummary();
    summary.setTotalIncome(income);
    summary.setIncomeCount(incomeCount);
    summary.setTotalExpense(expense);
    summary.setExpenseCount(expenseCount);
    return summary;
  }

  private static LocalDate minDate(List<TransactionDTO> transactions) {
    return transactions.stream().map(TransactionDTO::getTransDate).filter(d -> d != null)
      .min(LocalDate::compareTo).orElse(null);
  }

  private static LocalDate maxDate(List<TransactionDTO> transactions) {
    return transactions.stream().map(TransactionDTO::getTransDate).filter(d -> d != null)
      .max(LocalDate::compareTo).orElse(null);
  }

  // ==================== 单元格取值 ====================

  /** 取单元格，空白时回溯合并区域左上角 */
  private Cell mergedCell(Row row, Integer col) {
    if (row == null || col == null) {
      return null;
    }
    Cell cell = row.getCell(col);
    if (cell != null && !rawCellText(cell).isEmpty()) {
      return cell;
    }
    List<CellRangeAddress> regions = mergedByRow.get(row.getRowNum());
    if (regions == null) {
      return cell;
    }
    for (CellRangeAddress region : regions) {
      if (region.isInRange(row.getRowNum(), col)) {
        Row top = sheet.getRow(region.getFirstRow());
        return top == null ? cell : top.getCell(region.getFirstColumn());
      }
    }
    return cell;
  }

  private static Cell cellAt(Row row, int col) {
    return row == null ? null : row.getCell(col);
  }

  private String textOf(Cell cell) {
    return normalizeCellText(cell).trim();
  }

  private String normalizeCellText(Cell cell) {
    if (cell == null) {
      return "";
    }
    CellType type = cell.getCellType();
    if (type == CellType.FORMULA) {
      type = cell.getCachedFormulaResultType();
    }
    if (type == CellType.NUMERIC) {
      if (DateUtil.isCellDateFormatted(cell)) {
        LocalDateTime dt = cell.getLocalDateTimeCellValue();
        if (dt.toLocalTime().equals(LocalTime.MIDNIGHT)) {
          return dt.toLocalDate().toString();
        }
        return dt.toString().replace('T', ' ');
      }
      return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
    }
    try {
      return formatter.formatCellValue(cell);
    } catch (Exception e) {
      return "";
    }
  }

  private static String rawCellText(Cell cell) {
    if (cell == null) {
      return "";
    }
    CellType type = cell.getCellType();
    if (type == CellType.FORMULA) {
      type = cell.getCachedFormulaResultType();
    }
    try {
      return switch (type) {
        case STRING -> cell.getStringCellValue();
        case NUMERIC -> String.valueOf(cell.getNumericCellValue());
        case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
        default -> "";
      };
    } catch (Exception e) {
      return "";
    }
  }

  private static BigDecimal amountOf(Cell cell) {
    if (cell == null) {
      return null;
    }
    CellType type = cell.getCellType();
    if (type == CellType.FORMULA) {
      type = cell.getCachedFormulaResultType();
    }
    if (type == CellType.NUMERIC) {
      return BigDecimal.valueOf(cell.getNumericCellValue());
    }
    if (type != CellType.STRING) {
      return null;
    }
    String text = cell.getStringCellValue().trim();
    if (text.isEmpty()) {
      return null;
    }
    boolean negative = text.startsWith("(") && text.endsWith(")")
      || text.startsWith("（") && text.endsWith("）");
    String cleaned = text.replace(",", "").replace("，", "")
      .replaceAll("[¥￥$元人民币\\s]", "");
    Matcher m = NUMBER_TEXT.matcher(cleaned);
    if (!m.find()) {
      return null;
    }
    BigDecimal value = new BigDecimal(m.group());
    return negative ? value.abs().negate() : value;
  }

  private static BigDecimal scale(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
  }

  private static Integer intOf(Cell cell) {
    BigDecimal value = amountOf(cell);
    return value == null ? null : value.intValue();
  }

  private static LocalDateTime dateTimeOf(Cell cell) {
    if (cell == null) {
      return null;
    }
    CellType type = cell.getCellType();
    if (type == CellType.FORMULA) {
      type = cell.getCachedFormulaResultType();
    }
    if (type == CellType.NUMERIC) {
      if (DateUtil.isCellDateFormatted(cell)) {
        return cell.getLocalDateTimeCellValue();
      }
      double value = cell.getNumericCellValue();
      if (value >= 19000101 && value <= 21001231) {
        LocalDate date = parseCompactDate(String.valueOf((long) value));
        return date == null ? null : date.atStartOfDay();
      }
      if (DateUtil.isValidExcelDate(value) && value > 20000 && value < 80000) {
        return DateUtil.getLocalDateTime(value);
      }
      return null;
    }
    if (type != CellType.STRING) {
      return null;
    }
    String text = cell.getStringCellValue().trim();
    LocalDate date = dateOf(text);
    if (date == null) {
      return null;
    }
    Matcher time = TIME_TEXT.matcher(text);
    LocalTime localTime = time.find() ? toTime(time.group(1), time.group(2), time.group(3)) : null;
    return (localTime == null ? date.atStartOfDay() : LocalDateTime.of(date, localTime));
  }

  private static LocalDate dateOf(Cell cell) {
    LocalDateTime dateTime = dateTimeOf(cell);
    return dateTime == null ? null : dateTime.toLocalDate();
  }

  private static LocalDate dateOf(String text) {
    if (text == null) {
      return null;
    }
    String value = text.trim();
    LocalDate compact = parseCompactDate(value);
    if (compact != null) {
      return compact;
    }
    Matcher m = DATE_TEXT.matcher(value);
    if (!m.find()) {
      return null;
    }
    try {
      return LocalDate.of(Integer.parseInt(m.group(1)),
        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    } catch (Exception e) {
      return null;
    }
  }

  private static LocalDate parseCompactDate(String value) {
    Matcher m = COMPACT_DATE.matcher(value.replaceAll("[\\s\\u00A0\\u3000]", ""));
    if (!m.matches()) {
      return null;
    }
    try {
      return LocalDate.of(Integer.parseInt(m.group(1)),
        Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    } catch (Exception e) {
      return null;
    }
  }

  private static String timeOf(Cell cell) {
    if (cell == null) {
      return null;
    }
    CellType type = cell.getCellType();
    if (type == CellType.FORMULA) {
      type = cell.getCachedFormulaResultType();
    }
    if (type == CellType.NUMERIC) {
      if (DateUtil.isCellDateFormatted(cell)) {
        LocalDateTime dt = cell.getLocalDateTimeCellValue();
        if (!dt.toLocalTime().equals(LocalTime.MIDNIGHT)) {
          return dt.toLocalTime().toString();
        }
      }
      return null;
    }
    if (type != CellType.STRING) {
      return null;
    }
    Matcher m = TIME_TEXT.matcher(cell.getStringCellValue());
    return m.find() ? m.group(1) + ":" + m.group(2) + ":" + (m.group(3) == null ? "00" : m.group(3)) : null;
  }

  private static LocalTime toTime(String hour, String minute, String second) {
    try {
      return LocalTime.of(Integer.parseInt(hour), Integer.parseInt(minute),
        second == null ? 0 : Integer.parseInt(second));
    } catch (Exception e) {
      return null;
    }
  }

  private static String normalizeTime(String time) {
    Matcher m = TIME_TEXT.matcher(time);
    if (!m.find()) {
      return "00:00:00";
    }
    return String.format("%02d:%02d:%02d", Integer.parseInt(m.group(1)),
      Integer.parseInt(m.group(2)), m.group(3) == null ? 0 : Integer.parseInt(m.group(3)));
  }

  private static String normalizeDirection(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    String value = text.trim();
    if (value.contains("其他")) {
      return "其他";
    }
    boolean in = value.contains("收") || value.contains("入") || value.contains("贷") || value.contains("存");
    boolean out = value.contains("支") || value.contains("出") || value.contains("借") || value.contains("付") || value.contains("取");
    if (in && !out) {
      return "入";
    }
    if (out && !in) {
      return "出";
    }
    return null;
  }

  /** 表头区提取结果 */
  private static final class Meta {
    String holder;
    String accountNo;
    String bankName;
    String currency;
    LocalDate startDate;
    LocalDate endDate;
    String rawHeaderText;
  }
}
