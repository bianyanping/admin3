package tech.wetech.admin3.ocrApi.layout;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class StatementLayout {

  private String bankCode;                          // "CMB"
  private String bankNameKeyword;                   // "招商银行"
  private Pattern bankNamePattern;                  // 宽松正则，可选
  private List<String> headerFingerprints;          // 表头特征标签，可选
  private int priority = 0;                         // 多命中时优先级

  private Map<String, String> headerLabels;         // "账号/卡号：" -> "accountNo"
  private Map<String, String> summaryLabels;        // "支出合计：" -> "totalExpense"

  private TransactionRowDetector rowDetector;
  private List<ColumnRule> columns;
  private OrderedTextRule orderedTextRule;
  private List<Pattern> ignorePatterns;

  private List<String> expenseKeywords;   // 支出关键词
  private List<String> incomeKeywords;    // 收入关键词// 兜底文本过滤词

  // ---- getters / setters 全部生成 ----
  public String getBankCode() {
    return bankCode;
  }

  public void setBankCode(String bankCode) {
    this.bankCode = bankCode;
  }

  public String getBankNameKeyword() {
    return bankNameKeyword;
  }

  public void setBankNameKeyword(String bankNameKeyword) {
    this.bankNameKeyword = bankNameKeyword;
  }

  public Pattern getBankNamePattern() {
    return bankNamePattern;
  }

  public void setBankNamePattern(Pattern bankNamePattern) {
    this.bankNamePattern = bankNamePattern;
  }

  public List<String> getHeaderFingerprints() {
    return headerFingerprints;
  }

  public void setHeaderFingerprints(List<String> headerFingerprints) {
    this.headerFingerprints = headerFingerprints;
  }

  public int getPriority() {
    return priority;
  }

  public void setPriority(int priority) {
    this.priority = priority;
  }

  public Map<String, String> getHeaderLabels() {
    return headerLabels;
  }

  public void setHeaderLabels(Map<String, String> headerLabels) {
    this.headerLabels = headerLabels;
  }

  public Map<String, String> getSummaryLabels() {
    return summaryLabels;
  }

  public void setSummaryLabels(Map<String, String> summaryLabels) {
    this.summaryLabels = summaryLabels;
  }

  public TransactionRowDetector getRowDetector() {
    return rowDetector;
  }

  public void setRowDetector(TransactionRowDetector rowDetector) {
    this.rowDetector = rowDetector;
  }

  public List<ColumnRule> getColumns() {
    return columns;
  }

  public void setColumns(List<ColumnRule> columns) {
    this.columns = columns;
  }

  public OrderedTextRule getOrderedTextRule() {
    return orderedTextRule;
  }

  public void setOrderedTextRule(OrderedTextRule orderedTextRule) {
    this.orderedTextRule = orderedTextRule;
  }

  public List<Pattern> getIgnorePatterns() {
    return ignorePatterns;
  }

  public void setIgnorePatterns(List<Pattern> ignorePatterns) {
    this.ignorePatterns = ignorePatterns;
  }

  public List<String> getExpenseKeywords() {
    return expenseKeywords;
  }

  public void setExpenseKeywords(List<String> expenseKeywords) {
    this.expenseKeywords = expenseKeywords;
  }

  public List<String> getIncomeKeywords() {
    return incomeKeywords;
  }

  public void setIncomeKeywords(List<String> incomeKeywords) {
    this.incomeKeywords = incomeKeywords;
  }
}
