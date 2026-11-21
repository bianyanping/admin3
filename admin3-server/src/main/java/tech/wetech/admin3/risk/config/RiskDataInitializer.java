package tech.wetech.admin3.risk.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tech.wetech.admin3.risk.service.RiskDatabaseService;

/**
 * 启动时补齐风险数据库的预置标签与消费型剔除规则。
 */
@Component
public class RiskDataInitializer implements ApplicationRunner {

  private final RiskDatabaseService riskDatabaseService;

  public RiskDataInitializer(RiskDatabaseService riskDatabaseService) {
    this.riskDatabaseService = riskDatabaseService;
  }

  @Override
  public void run(ApplicationArguments args) {
    // 先确保唯一键排序规则正确，再补齐预置数据
    riskDatabaseService.ensureSchema();
    riskDatabaseService.ensurePresets();
  }
}
