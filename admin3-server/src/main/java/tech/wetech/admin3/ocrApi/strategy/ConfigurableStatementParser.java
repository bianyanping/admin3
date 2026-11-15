package tech.wetech.admin3.ocrApi.strategy;

import org.json.JSONArray;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.ocrApi.layout.StatementLayout;

public class ConfigurableStatementParser implements BankStatementParserStrategy {

  private final StatementLayout layout;
  private final StatementLayoutMatcher matcher;
  private final ConfigurableStatementParserCore core;

  public ConfigurableStatementParser(StatementLayout layout) {
    this.layout = layout;
    this.matcher = new StatementLayoutMatcher(layout);
    this.core = new ConfigurableStatementParserCore(layout);
  }

  @Override
  public String bankCode() {
    return layout.getBankCode();
  }

  @Override
  public boolean supports(ParseContext context) {
    return matcher.matches(context);
  }

  @Override
  public BankStatementDTO parse(JSONArray pages) {
    return core.parse(pages);
  }

  @Override
  public int priority() {
    return layout.getPriority();
  }

  public StatementLayout getLayout() {
    return layout;
  }
}
