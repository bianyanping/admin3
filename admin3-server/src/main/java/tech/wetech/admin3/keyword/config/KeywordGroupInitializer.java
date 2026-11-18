package tech.wetech.admin3.keyword.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import tech.wetech.admin3.keyword.service.KeywordAnalysisService;
import tech.wetech.admin3.sys.repository.UserRepository;

/**
 * 启动时为内置管理员补齐关键词分组的预置数据（借款/还款、工资/货款）。
 * 其他用户的分组在首次访问时按需初始化。
 */
@Component
public class KeywordGroupInitializer implements ApplicationRunner {

  private final KeywordAnalysisService keywordAnalysisService;
  private final UserRepository userRepository;

  public KeywordGroupInitializer(KeywordAnalysisService keywordAnalysisService,
                                 UserRepository userRepository) {
    this.keywordAnalysisService = keywordAnalysisService;
    this.userRepository = userRepository;
  }

  @Override
  public void run(ApplicationArguments args) {
    userRepository.findByUsername("admin")
      .ifPresent(user -> keywordAnalysisService.ensurePresets(user.getId()));
  }
}
