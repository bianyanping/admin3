package tech.wetech.admin3.ocrApi.registry;

import tech.wetech.admin3.ocrApi.strategy.BankStatementParserStrategy;
import tech.wetech.admin3.ocrApi.strategy.ParseContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ParserRegistry {

  private final Map<String, BankStatementParserStrategy> byBankCode = new ConcurrentHashMap<>();
  private final List<BankStatementParserStrategy> all = new ArrayList<>();

  public synchronized void register(BankStatementParserStrategy strategy) {
    String code = strategy.bankCode();
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("bankCode 不能为空");
    }
    String upper = code.toUpperCase(Locale.ROOT);
    all.removeIf(s -> s.bankCode().equalsIgnoreCase(upper));
    all.add(strategy);
    byBankCode.put(upper, strategy);
  }

  public synchronized void registerAll(Collection<BankStatementParserStrategy> strategies) {
    strategies.forEach(this::register);
  }

  public BankStatementParserStrategy getByBankCode(String bankCode) {
    if (bankCode == null) return null;
    return byBankCode.get(bankCode.toUpperCase(Locale.ROOT));
  }

  public BankStatementParserStrategy resolve(ParseContext context) {
    BankStatementParserStrategy explicit = getByBankCode(context.getExplicitBankCode());
    if (explicit != null && explicit.supports(context)) {
      return explicit;
    }
    return findBestMatch(context);
  }

  private BankStatementParserStrategy findBestMatch(ParseContext context) {
    List<BankStatementParserStrategy> candidates = new ArrayList<>();
    for (BankStatementParserStrategy s : all) {
      if (s.supports(context)) candidates.add(s);
    }
    if (candidates.isEmpty()) return null;
    if (candidates.size() == 1) return candidates.get(0);
    candidates.sort(Comparator.comparingInt(BankStatementParserStrategy::priority).reversed());
    return candidates.get(0);
  }

  public Collection<BankStatementParserStrategy> all() {
    return Collections.unmodifiableList(all);
  }
}
