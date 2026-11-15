package tech.wetech.admin3.ocrApi.layout.config;

import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class GxrcbLayout {
  // 广西农商金额：可带 +/-，千分位是点，小数也是点
  private static final Pattern GX_AMOUNT_PATTERN =
    Pattern.compile("[+-]?[\\dTlIOS,]+(?:[.,][\\dTlIOS]{3})*[.,][\\dTlIOS]{2}");
  public static StatementLayout build() {
    StatementLayout layout = new StatementLayout();
    layout.setBankCode("GXRCB");
    layout.setBankNameKeyword("广西农商联合银行");
    layout.setPriority(10);

    // 表头：和招行一样
    Map<String, String> header = new LinkedHashMap<>();
    header.put("账号/卡号：", "accountNo");
    header.put("户名：", "accountHolder");
    header.put("日期：", "dateRange");
    layout.setHeaderLabels(header);

    // 汇总：和招行一样
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put("支出合计：", "totalExpense");
    summary.put("支出笔数：", "expenseCount");
    summary.put("收入合计：", "totalIncome");
    summary.put("收入笔数：", "incomeCount");
    summary.put("明细申请时间：", "applyTime");
    layout.setSummaryLabels(summary);

    // 行起点：序号 + 日期，但序号可能被 OCR 成 A/T2/缺失
    // 这里用宽松版 detector，见下面"改 3"
    layout.setRowDetector(new SeqDateLooseRowDetector());

    layout.setExpenseKeywords(List.of(
      "手续费", "工本费", "贷款还款", "货款还款", "收费", "扣息", "还款"));
    layout.setIncomeKeywords(List.of(
      "结息", "利息", "收入", "退款", "入账"));

    // 列规则：按模式匹配，顺序即优先级
    layout.setColumns(List.of(
      new ColumnRule("amount", GX_AMOUNT_PATTERN, 1,
        (tx, v) -> {
          String raw = v.trim();
          BigDecimal val = AmountNormalizer.normalize(raw);
          boolean explicitSign = raw.startsWith("+") || raw.startsWith("-");
          if (tx.getAmount() == null) {
            tx.setAmount(val);
            tx.setAmountSignExplicit(explicitSign);
          } else if (tx.getBalance() == null) {
            tx.setBalance(val);
          }
        }),// 方向：出/入，只取第一次
      new ColumnRule("direction", Pattern.compile("出|入"), 1,
        (tx, v) -> tx.setOcrDirection(v)),
      // 对方账号：纯数字且长度 >= 10（避开 00012、0002102 这类备注）
      new ColumnRule("counterpartyAccount", Pattern.compile("\\d{10,}"), 1,
        TransactionDTO::setCounterpartyAccount)
    ));

    // 兜底文本顺序：按这份数据的实际列顺序
    // 对方户名 → 用途 → 摘要 → 摘要内容 → 附言 → 备注
    layout.setOrderedTextRule(new OrderedTextRule(
      List.of("counterpartyName", "purpose", "summary", "summaryContent", "postscript", "remark"),
      List.of(
        TransactionDTO::setCounterpartyName,
        TransactionDTO::setPurpose,
        TransactionDTO::setSummary,
        (tx, v) -> { /* 摘要内容，DTO 没这个字段就先忽略 */ },
        TransactionDTO::setPostscript,
        TransactionDTO::setRemark
      )
    ));

    // 过滤词：表头列名 + 页码 + 重复方向
    layout.setIgnorePatterns(List.of(
      // 汇总（精确前缀）
      Pattern.compile("^支出合计.*"), Pattern.compile("^支出笔数.*"),
      Pattern.compile("^收入合计.*"), Pattern.compile("^收入笔数.*"),
      Pattern.compile("^明细申请时间.*"),
      // 页码
      Pattern.compile("^共\\d+页第\\d+页$"),
      // 表头列名（锚定，避免误伤户名/用途）
      Pattern.compile("^序号$"), Pattern.compile("^交易时间$"),
      Pattern.compile("^支出/收入金额$"), Pattern.compile("^账户余额$"),
      Pattern.compile("^对方账号$"), Pattern.compile("^对方户名$"),
      Pattern.compile("^交易[果集]道$"), Pattern.compile("^用途$"),
      Pattern.compile("^摘[要篷]$"), Pattern.compile("^[汽意]要内容$"),
      Pattern.compile("^附[言吉]$"), Pattern.compile("^备[注汪]$"),
      Pattern.compile("^专用章$"), Pattern.compile("^电子银行业务$"),
      Pattern.compile("^账户交易明细$"), Pattern.compile("^法本江衣查合秀$")
    ));
    return layout;
  }
}
