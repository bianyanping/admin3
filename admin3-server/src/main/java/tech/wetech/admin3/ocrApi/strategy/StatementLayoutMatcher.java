package tech.wetech.admin3.ocrApi.strategy;

import tech.wetech.admin3.ocrApi.layout.StatementLayout;

import java.util.List;

public class StatementLayoutMatcher {

  private final StatementLayout layout;

  public StatementLayoutMatcher(StatementLayout layout) {
    this.layout = layout;
  }

  public boolean matches(ParseContext ctx) {
    // 1. 显式指定
    if (ctx.getExplicitBankCode() != null) {
      return ctx.getExplicitBankCode().equalsIgnoreCase(layout.getBankCode());
    }
    // 2. 文件名
    String keyword = layout.getBankNameKeyword();
    if (ctx.getFileName() != null && keyword != null
      && ctx.getFileName().contains(keyword)) {
      return true;
    }
    // 3. 页面文本精确包含银行名
    List<String> words = ctx.getAllWords();
    if (keyword != null && words.stream().anyMatch(w -> w.contains(keyword))) {
      return true;
    }
    // 4. 宽松正则
    if (layout.getBankNamePattern() != null
      && words.stream().anyMatch(w -> layout.getBankNamePattern().matcher(w).find())) {
      return true;
    }
    // 5. 表头特征兜底：至少命中 2 个
    if (layout.getHeaderFingerprints() != null && !layout.getHeaderFingerprints().isEmpty()) {
      long hit = layout.getHeaderFingerprints().stream()
        .filter(fp -> words.stream().anyMatch(w -> w.startsWith(fp)))
        .count();
      return hit >= Math.min(2, layout.getHeaderFingerprints().size());
    }
    return false;
  }
}
