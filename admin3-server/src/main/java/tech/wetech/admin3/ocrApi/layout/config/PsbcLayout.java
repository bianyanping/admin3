package tech.wetech.admin3.ocrApi.layout.config;

import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class PsbcLayout {

  private PsbcLayout() {}

  // 金额：支持 4.50、,7.17、30,138.89、110,767.514.60（OCR 错）
  private static final Pattern AMOUNT_PATTERN =
    Pattern.compile("[+-]?[\\d,]*\\d(?:\\.\\d{2})?");

  // 日期：2025-09-03、202509-03、2025-1028 等
  private static final Pattern DATE_LOOSE =
    Pattern.compile("\\d{4}[-年]?\\d{1,2}[-月]?\\d{1,2}日?");

  // 时间：9:14:43、9:14:4%
  private static final Pattern TIME_LOOSE =
    Pattern.compile("\\d{1,2}:\\d{1,2}:\\d{1,2}");

  public static StatementLayout build() {
    StatementLayout layout = new StatementLayout();
    layout.setBankCode("PSBC");
    layout.setBankNameKeyword("中国邮政储蓄银行");
    layout.setBankNamePattern(Pattern.compile("中国.{0,3}政储.{0,2}银行"));
    layout.setPriority(10);

    // 表头：邮储的账号、户名、日期是"标签词"和"值词"分开，startsWith 匹配不到，
    // 这里只配能匹配上的标签，实际值在 Core 里按"标签后一个词"取（见下面说明）
    Map<String, String> header = new LinkedHashMap<>();
    header.put("账号", "accountNo");
    header.put("户名", "accountHolder");
    header.put("起止日期", "dateRange");
    layout.setHeaderLabels(header);

    // 汇总：邮储这份数据里没有"支出合计："这种标签，收入总金额在表头
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put("收入总金额", "totalIncome");
    summary.put("收入总笔数", "incomeCount");
    layout.setSummaryLabels(summary);

    // 行起点：邮储第 1 页无序号，交易行以"流水号（16位数字）"或"对方行名"开头
    // 用"日期 + 金额"组合识别更稳：连续两个日期 + 至少一个金额
    layout.setRowDetector(new PsbcRowDetector());

    // 列规则
    layout.setColumns(List.of(
      // 支出金额：第一个金额（第 1 个金额词）
      new ColumnRule("amount", AMOUNT_PATTERN, 1, (tx, v) -> {
        if (tx.getAmount() == null) {
          tx.setAmount(AmountNormalizer.normalize(v));
        }
      }),
      // 收入金额：第二个金额词，如果非 0 且支出为 0，则它是金额
      new ColumnRule("amountOrIncome", AMOUNT_PATTERN, 2, (tx, v) -> {
        var val = AmountNormalizer.normalize(v);
        if (val.signum() > 0 && (tx.getAmount() == null
          || tx.getAmount().signum() == 0)) {
          tx.setAmount(val);
        } else if (tx.getBalance() == null) {
          tx.setBalance(val);
        }
      }),
      // 余额：第三个金额词
      new ColumnRule("balance", AMOUNT_PATTERN, 3, (tx, v) -> {
        if (tx.getBalance() == null) {
          tx.setBalance(AmountNormalizer.normalize(v));
        }
      }),
      // 对方账号：纯数字且长度 >= 10
      new ColumnRule("counterpartyAccount",
        Pattern.compile("\\d{10,}"), 1,
        TransactionDTO::setCounterpartyAccount)
    ));

    // 兜底文本顺序：邮储的列顺序是
    // 对方行名 → 用途 → 对方户名 → 摘要 → 时间 → 对方行名后缀 → 路由号
    layout.setOrderedTextRule(new OrderedTextRule(
      List.of("channel", "purpose", "counterpartyName", "summary",
        "postscript", "remark"),
      List.of(
        TransactionDTO::setChannel,
        TransactionDTO::setPurpose,
        TransactionDTO::setCounterpartyName,
        TransactionDTO::setSummary,
        TransactionDTO::setPostscript,
        TransactionDTO::setRemark
      )
    ));

    // 过滤词：表头、页码、OCR 限流空页标记
    layout.setIgnorePatterns(List.of(
      Pattern.compile("^序号$"), Pattern.compile("^交易时间$"),
      Pattern.compile("^记账日期$"), Pattern.compile("^支出金额$"),
      Pattern.compile("^收入金额$"), Pattern.compile("^对方账号$"),
      Pattern.compile("^对方户名$"), Pattern.compile("^对方行名$"),
      Pattern.compile("^用途$"), Pattern.compile("^附[音言]$"),
      Pattern.compile("^摘要$"), Pattern.compile("^交得流水号$"),
      Pattern.compile("^会局路由号$"), Pattern.compile("^账号$"),
      Pattern.compile("^户名.*$"), Pattern.compile("^起止日期$"),
      Pattern.compile("^收入总金额$"), Pattern.compile("^收入总笔数$"),
      Pattern.compile("^在导出时间$"), Pattern.compile("^明细申请时间.*$"),
      Pattern.compile("^第\\d+页/共\\d+页$"),
      Pattern.compile("^中国邮.*$"), Pattern.compile("^中国官.*$"),
      Pattern.compile("^中国改.*$"), Pattern.compile("^中国闻.*$"),
      Pattern.compile("^中国笔.*$"), Pattern.compile("^中国管.*$"),
      Pattern.compile("^中回.*$"), Pattern.compile("^中曰.*$"),
      Pattern.compile("^银行股.*$"), Pattern.compile("^根行股.*$"),
      Pattern.compile("^银行围.*$"), Pattern.compile("^根行围.*$"),
      Pattern.compile("^导中国.*$"), Pattern.compile("^面中国.*$")
    ));

    return layout;
  }
}
