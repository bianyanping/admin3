package tech.wetech.admin3.ocrApi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tech.wetech.admin3.ocrApi.BankStatementParserFacade;
import tech.wetech.admin3.ocrApi.layout.config.CmbLayout;
import tech.wetech.admin3.ocrApi.registry.ParserRegistry;
import tech.wetech.admin3.ocrApi.strategy.AbcHxTableStatementParser;
import tech.wetech.admin3.ocrApi.strategy.BankStatementParserStrategy;
import tech.wetech.admin3.ocrApi.strategy.ConfigurableStatementParser;
import tech.wetech.admin3.ocrApi.strategy.GlgmczyhTableStatementParser;
import tech.wetech.admin3.ocrApi.strategy.GxrcbTableStatementParser;
import tech.wetech.admin3.ocrApi.strategy.PsbcTableStatementParser;

import java.util.List;

@Configuration
public class ParserConfig {

  @Bean
  public BankStatementParserStrategy cmbParser() {
    return new ConfigurableStatementParser(CmbLayout.build());
  }

  @Bean
  public BankStatementParserStrategy psbcParser() {
    return new PsbcTableStatementParser();
  }

  @Bean
  public BankStatementParserStrategy gxrcbParser() {
    return new GxrcbTableStatementParser();
  }

  @Bean
  public BankStatementParserStrategy glgmczyhParser() {
    return new GlgmczyhTableStatementParser();
  }

  /** 农行「对公交易明细清单」扫描件/图片（OCR 表格） */
  @Bean
  public BankStatementParserStrategy abcHxTableParser() {
    return new AbcHxTableStatementParser();
  }

  @Bean
  public ParserRegistry parserRegistry(List<BankStatementParserStrategy> strategies) {
    ParserRegistry registry = new ParserRegistry();
    registry.registerAll(strategies);
    return registry;
  }

  @Bean
  public BankStatementParserFacade bankStatementParserFacade(ParserRegistry registry) {
    return new BankStatementParserFacade(registry);
  }
}
