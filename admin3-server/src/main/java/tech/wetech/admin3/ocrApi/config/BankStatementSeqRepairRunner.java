package tech.wetech.admin3.ocrApi.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tech.wetech.admin3.ocrApi.service.BankStatementService;

/**
 * 启动时修复历史流水明细的重复序号。
 *
 * <p>同账期分页图片合并后，早期版本会把每页的 1..N 序号直接拼在一起，
 * 导致明细列表出现重复序号。这里只处理序号确实重复/缺失的流水单，按交易日期/时间重排。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class BankStatementSeqRepairRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BankStatementSeqRepairRunner.class);

  private final BankStatementService bankStatementService;

  public BankStatementSeqRepairRunner(BankStatementService bankStatementService) {
    this.bankStatementService = bankStatementService;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      int repaired = bankStatementService.renumberDuplicateSeqNo();
      if (repaired > 0) {
        log.info("已修复 {} 份流水单的重复交易序号", repaired);
      }
    } catch (Exception e) {
      log.warn("修复流水交易序号失败：{}", e.getMessage());
    }
  }
}
