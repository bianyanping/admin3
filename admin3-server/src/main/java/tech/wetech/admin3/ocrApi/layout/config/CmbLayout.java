package tech.wetech.admin3.ocrApi.layout.config;

import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.layout.*;

import java.util.*;
import java.util.regex.Pattern;

public final class CmbLayout {

  private CmbLayout() {}

  private static final Pattern AMOUNT_PATTERN = Pattern.compile("[+-]?[\\d,]+\\.\\d{2}");
  private static final Pattern DIRECTION_PATTERN = Pattern.compile("出|入");
  private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\d{8,}");

  public static StatementLayout build() {
    StatementLayout layout = new StatementLayout();
    layout.setBankCode("CMB");
    layout.setBankNameKeyword("招商银行");
    layout.setBankNamePattern(Pattern.compile("招.{0,2}商?\\s*银\\s*行"));
    layout.setHeaderFingerprints(List.of("账号/卡号：", "明细申请时间："));
    layout.setPriority(10);

    // 表头：标签 -> 语义字段
    Map<String, String> header = new LinkedHashMap<>();
    header.put("账号/卡号：", "accountNo");
    header.put("户名：", "accountHolder");
    header.put("日期：", "dateRange");
    layout.setHeaderLabels(header);

    // 汇总
    Map<String, String> summary = new LinkedHashMap<>();
    summary.put("支出合计：", "totalExpense");
    summary.put("支出笔数：", "expenseCount");
    summary.put("收入合计：", "totalIncome");
    summary.put("收入笔数：", "incomeCount");
    summary.put("明细申请时间：", "applyTime");
    layout.setSummaryLabels(summary);

    // 行起点
    layout.setRowDetector(new SeqDateLooseRowDetector());

    // 列规则：amount 第 1 个金额，balance 第 2 个金额
    layout.setColumns(List.of(
      new ColumnRule("amount", AMOUNT_PATTERN, 1,
        (tx, v) -> tx.setAmount(AmountNormalizer.normalize(v))),
      new ColumnRule("balance", AMOUNT_PATTERN, 2,
        (tx, v) -> tx.setBalance(AmountNormalizer.normalize(v))),
      new ColumnRule("direction", DIRECTION_PATTERN, 1,
        TransactionDTO::setDirection),
      new ColumnRule("counterpartyAccount", ACCOUNT_PATTERN, 1,
        TransactionDTO::setCounterpartyAccount)
    ));

    // 兜底文本顺序
    layout.setOrderedTextRule(new OrderedTextRule(
      List.of("counterpartyName", "channel", "purpose", "summary", "postscript", "remark"),
      List.of(
        TransactionDTO::setCounterpartyName,
        TransactionDTO::setChannel,
        TransactionDTO::setPurpose,
        TransactionDTO::setSummary,
        TransactionDTO::setPostscript,
        TransactionDTO::setRemark
      )
    ));

    layout.setIgnorePatterns(List.of(
      Pattern.compile(".*页.*"), Pattern.compile(".*合计.*"),
      Pattern.compile(".*笔数.*"), Pattern.compile("序[号弓]"),
      Pattern.compile("[交文]易.*"), Pattern.compile("[账帐]号.*"),
      Pattern.compile(".*金额.*"), Pattern.compile(".*余额.*"),
      Pattern.compile("[交文]易[果集]道"), Pattern.compile("用[途造]"),
      Pattern.compile("[摘篷意]要.*"), Pattern.compile("附[言吉]"),
      Pattern.compile("备[注汪]"), Pattern.compile(".*内容.*")
    ));

    return layout;
  }
}
