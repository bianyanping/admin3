package tech.wetech.admin3.risk.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.model.BankStatementTransaction;
import tech.wetech.admin3.ocrApi.repository.BankStatementRepository;
import tech.wetech.admin3.ocrApi.repository.BankStatementTransactionRepository;
import tech.wetech.admin3.ocrApi.service.BankStatementScopeService;
import tech.wetech.admin3.risk.model.RiskCounterparty;
import tech.wetech.admin3.risk.model.RiskTag;
import tech.wetech.admin3.risk.model.RiskTagLink;
import tech.wetech.admin3.risk.repository.RiskCounterpartyRepository;
import tech.wetech.admin3.risk.repository.RiskTagLinkRepository;
import tech.wetech.admin3.risk.repository.RiskTagRepository;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 查阅流水时的交易对手风险警示。
 *
 * <p>比对两类库：
 * <ol>
 *   <li>资方库：挂「民间资方」标签的交易对手</li>
 *   <li>黑名单：挂「黑名单对手」的交易对手、挂「黑名单客户」的客户户名</li>
 * </ol>
 * 匹配分两档：仅名称命中（黄色）、名称+卡号同时命中（红色）。
 * 名称按全半角归一 + 去空白 + 小写比对，卡号按纯数字比对。
 * 未打资方/黑名单标签的普通对手不参与比对。
 */
@Service
public class RiskAlertService {

  private static final String LIB_FUNDER = "FUNDER";
  private static final String LIB_BLACKLIST_CP = "BLACKLIST_CP";
  private static final String LIB_BLACKLIST_CUSTOMER = "BLACKLIST_CUSTOMER";

  /** 同一档位下多库命中时的优先级：黑名单对手 > 黑名单客户 > 资方 */
  private static final Map<String, Integer> LIB_PRIORITY = Map.of(
    LIB_BLACKLIST_CP, 1,
    LIB_BLACKLIST_CUSTOMER, 2,
    LIB_FUNDER, 3
  );

  /** 单个对手最多返回的逐笔行数，避免命中过多时响应过大；笔数与金额始终按全量统计 */
  private static final int MAX_GROUP_ROWS = 200;

  private final RiskTagRepository tagRepository;
  private final RiskTagLinkRepository tagLinkRepository;
  private final RiskCounterpartyRepository counterpartyRepository;
  private final BankStatementRepository statementRepository;
  private final BankStatementTransactionRepository transactionRepository;
  private final BankStatementScopeService scopeService;
  private final RiskDatabaseService riskDatabaseService;

  public RiskAlertService(RiskTagRepository tagRepository,
                          RiskTagLinkRepository tagLinkRepository,
                          RiskCounterpartyRepository counterpartyRepository,
                          BankStatementRepository statementRepository,
                          BankStatementTransactionRepository transactionRepository,
                          BankStatementScopeService scopeService,
                          RiskDatabaseService riskDatabaseService) {
    this.tagRepository = tagRepository;
    this.tagLinkRepository = tagLinkRepository;
    this.counterpartyRepository = counterpartyRepository;
    this.statementRepository = statementRepository;
    this.transactionRepository = transactionRepository;
    this.scopeService = scopeService;
    this.riskDatabaseService = riskDatabaseService;
  }

  // ==================== 对外接口 ====================

  /**
   * 风险警示分析。
   *
   * @param scope PAGE=只比对传入的当前页行；STATEMENT=整份流水单；HOLDER=某个户名全部流水；ALL=全部可见流水
   */
  public AlertResult analyze(Long userId, AlertRequest request) {
    String scope = request.scope() == null ? "PAGE" : request.scope().toUpperCase(Locale.ROOT);
    List<BankStatement> scopeStatements = resolveScope(userId, scope, request.statementId(), request.accountHolder());

    Index index = buildIndex(userId);

    Map<String, AlertMark> marks = new LinkedHashMap<>();
    for (Item item : request.items() == null ? List.<Item>of() : request.items()) {
      Match match = index.match(item.name(), item.account());
      if (match != null) {
        marks.put(item.key(), toMark(match, item.date(), item.direction(), item.amount()));
      }
    }

    AlertSummary summary = "PAGE".equals(scope) ? null : summarize(scopeStatements, index);
    return new AlertResult(marks, summary);
  }

  /**
   * 导出用：给每笔流水生成一行风险标记文本，无命中不出现在结果里。
   */
  public Map<Long, String> riskLabels(Long userId, List<BankStatementTransaction> transactions) {
    Index index = buildIndex(userId);
    Map<Long, String> labels = new HashMap<>();
    for (BankStatementTransaction t : transactions) {
      Match match = index.match(t.getCounterpartyName(), t.getCounterpartyAccount());
      if (match == null) {
        continue;
      }
      String tier = match.doubleHit() ? "（名称+卡号）"
        : match.nameMatched() ? "（名称）" : "（卡号）";
      labels.put(t.getId(), shortLabel(match.target().library()) + "·"
        + match.target().name() + tier);
    }
    return labels;
  }

  // ==================== 范围 ====================

  private List<BankStatement> resolveScope(Long userId, String scope, Long statementId, String accountHolder) {
    if ("STATEMENT".equals(scope)) {
      if (statementId == null) {
        throw new BusinessException(CommonResultStatus.PARAM_ERROR, "缺少流水单 id");
      }
      BankStatement statement = statementRepository.findById(statementId)
        .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
      scopeService.requireVisible(userId, statement);
      return List.of(statement);
    }
    if ("HOLDER".equals(scope)) {
      String holder = accountHolder == null ? "" : accountHolder.trim();
      if (holder.isEmpty()) {
        throw new BusinessException(CommonResultStatus.PARAM_ERROR, "请选择户名");
      }
      return visibleStatements(userId).stream()
        .filter(s -> s.getAccountHolder() != null && s.getAccountHolder().trim().equals(holder))
        .toList();
    }
    if ("ALL".equals(scope)) {
      return visibleStatements(userId);
    }
    return List.of();
  }

  private List<BankStatement> visibleStatements(Long userId) {
    if (userId == null) {
      return List.of();
    }
    BankStatementScopeService.Visibility visibility = scopeService.visibilityOf(userId);
    if (visibility.nothing()) {
      return List.of();
    }
    Specification<BankStatement> spec = (root, query, cb) -> {
      List<Predicate> visible = new ArrayList<>();
      if (!visibility.orgIds().isEmpty()) {
        visible.add(cb.coalesce(root.get("orgId"), BankStatementScopeService.ROOT_ORG_ID)
          .in(visibility.orgIds()));
      }
      if (!visibility.sharedIds().isEmpty()) {
        visible.add(root.get("id").in(visibility.sharedIds()));
      }
      return cb.or(visible.toArray(new Predicate[0]));
    };
    return statementRepository.findAll(spec);
  }

  // ==================== 索引 ====================

  private Index buildIndex(Long userId) {
    if (userId == null) {
      return new Index(List.of());
    }
    // 保证当前用户的对手主数据已按可见流水沉淀，避免账号维度缺失
    riskDatabaseService.syncIfNeeded(userId, false);
    Map<String, CustomerAgg> customers = new LinkedHashMap<>();
    for (BankStatement s : visibleStatements(userId)) {
      String name = cleanName(s.getAccountHolder());
      String key = riskKey(name);
      if (key.isEmpty()) {
        continue;
      }
      CustomerAgg agg = customers.computeIfAbsent(key, k -> new CustomerAgg());
      agg.name = name;
      String digits = digitsOnly(s.getAccountNo());
      if (!digits.isEmpty()) {
        agg.accounts.add(digits);
      }
    }

    // 对手库：只取当前用户自己沉淀的数据（管理员即全量），用于资方 / 黑名单对手的名称与卡号比对
    Map<String, CpInfo> counterparties = new LinkedHashMap<>();
    for (RiskCounterparty cp : counterpartyRepository.findByOwnerUserId(userId)) {
      String key = riskKey(cp.getNameKey());
      if (key.isEmpty()) {
        continue;
      }
      CpInfo info = counterparties.computeIfAbsent(key, k -> new CpInfo());
      if (cp.getDisplayName() != null && !cp.getDisplayName().isBlank()) {
        info.display = cleanName(cp.getDisplayName());
      }
      if (cp.getAccountNos() != null) {
        for (String part : cp.getAccountNos().split(",")) {
          String digits = digitsOnly(part);
          if (!digits.isEmpty()) {
            info.accounts.add(digits);
          }
        }
      }
    }

    Map<Long, String> tagCodeById = tagRepository.findAll().stream()
      .collect(Collectors.toMap(RiskTag::getId, RiskTag::getCode, (a, b) -> a));
    boolean admin = scopeService.isAdmin(userId);
    Set<String> funderKeys = new LinkedHashSet<>();
    Set<String> blacklistCpKeys = new LinkedHashSet<>();
    Set<String> blacklistCustomerKeys = new LinkedHashSet<>();
    for (RiskTagLink link : tagLinkRepository.findAll()) {
      // 只比对当前用户可见的标签：系统标签全局可见，手工标签普通用户仅本人、管理员全部
      if (!tagLinkVisible(link, userId, admin)) {
        continue;
      }
      String code = tagCodeById.get(link.getTagId());
      if (code == null) {
        continue;
      }
      String key = riskKey(link.getSubjectKey());
      if (key.isEmpty()) {
        continue;
      }
      if ("FUNDER".equals(code) && RiskTag.SUBJECT_COUNTERPARTY.equals(link.getSubjectType())) {
        funderKeys.add(key);
      } else if ("BLACKLIST_CP".equals(code) && RiskTag.SUBJECT_COUNTERPARTY.equals(link.getSubjectType())) {
        blacklistCpKeys.add(key);
      } else if ("BLACKLIST_CUSTOMER".equals(code) && RiskTag.SUBJECT_CUSTOMER.equals(link.getSubjectType())) {
        blacklistCustomerKeys.add(key);
      }
    }

    List<Target> targets = new ArrayList<>();
    for (String key : blacklistCpKeys) {
      CpInfo info = counterparties.get(key);
      targets.add(new Target(LIB_BLACKLIST_CP, "黑名单对手", displayOf(info, key),
        key, info == null ? Set.of() : Set.copyOf(info.accounts)));
    }
    for (String key : blacklistCustomerKeys) {
      CustomerAgg agg = customers.get(key);
      targets.add(new Target(LIB_BLACKLIST_CUSTOMER, "黑名单客户", displayOf(agg, key),
        key, agg == null ? Set.of() : Set.copyOf(agg.accounts)));
    }
    for (String key : funderKeys) {
      CpInfo info = counterparties.get(key);
      targets.add(new Target(LIB_FUNDER, "民间资方", displayOf(info, key),
        key, info == null ? Set.of() : Set.copyOf(info.accounts)));
    }
    return new Index(targets);
  }

  private boolean tagLinkVisible(RiskTagLink link, Long userId, boolean admin) {
    if (RiskTagLink.SOURCE_SYSTEM.equals(link.getSource())) {
      return true;
    }
    if (admin) {
      return true;
    }
    return userId != null && userId.equals(link.getOwnerUserId());
  }

  private String displayOf(CpInfo info, String key) {
    return info != null && info.display != null && !info.display.isBlank() ? info.display : key;
  }

  private String displayOf(CustomerAgg agg, String key) {
    return agg != null && agg.name != null && !agg.name.isBlank() ? agg.name : key;
  }

  // ==================== 汇总 ====================

  private AlertSummary summarize(List<BankStatement> statements, Index index) {
    if (statements.isEmpty()) {
      return new AlertSummary(0, 0, BigDecimal.ZERO, List.of());
    }
    List<Long> ids = statements.stream().map(BankStatement::getId).toList();
    Specification<BankStatementTransaction> spec = (root, query, cb) ->
      root.get("statement").get("id").in(ids);
    List<BankStatementTransaction> txns = transactionRepository.findAll(spec);

    Map<String, GroupAgg> groups = new LinkedHashMap<>();
    int totalCount = 0;
    BigDecimal totalAmount = BigDecimal.ZERO;
    for (BankStatementTransaction t : txns) {
      Match match = index.match(t.getCounterpartyName(), t.getCounterpartyAccount());
      if (match == null) {
        continue;
      }
      totalCount++;
      BigDecimal amount = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount().abs();
      totalAmount = totalAmount.add(amount);
      String key = match.target().library() + "|" + match.target().nameKey();
      GroupAgg agg = groups.computeIfAbsent(key, k -> new GroupAgg(match.target()));
      agg.count++;
      agg.doubleHit = agg.doubleHit || match.doubleHit();
      agg.totalAmount = agg.totalAmount.add(amount);
      if (agg.rows.size() < MAX_GROUP_ROWS) {
        Long statementId = t.getStatement() == null ? null : t.getStatement().getId();
        agg.rows.add(toRow(t, statementId, match));
      } else {
        agg.truncated = true;
      }
    }

    List<AlertGroup> result = groups.values().stream()
      .sorted(Comparator.comparing((GroupAgg g) -> g.totalAmount).reversed()
        .thenComparingInt(g -> LIB_PRIORITY.getOrDefault(g.target.library(), 9)))
      .map(g -> {
        List<AlertRow> rows = new ArrayList<>(g.rows);
        rows.sort(Comparator.comparing(AlertRow::transDate,
            Comparator.nullsLast(Comparator.reverseOrder()))
          .thenComparing(AlertRow::transactionId, Comparator.nullsLast(Comparator.reverseOrder())));
        return new AlertGroup(g.target.name(), g.target.library(), g.target.libraryLabel(),
          g.doubleHit ? "NAME_ACCOUNT" : "NAME", g.count, g.totalAmount, g.truncated, rows);
      })
      .toList();
    return new AlertSummary(totalCount, result.size(), totalAmount, result);
  }

  private AlertRow toRow(BankStatementTransaction t, Long statementId, Match match) {
    return new AlertRow(t.getId(), statementId, t.getTransDate(), t.getDirection(),
      t.getAmount(), t.getCounterpartyName(), t.getCounterpartyAccount(),
      match.doubleHit() ? "NAME_ACCOUNT" : "NAME",
      match.target().library(), match.target().libraryLabel(), match.target().name(),
      hint(match, t.getTransDate(), t.getDirection(), t.getAmount()));
  }

  private AlertMark toMark(Match match, LocalDate date, String direction, BigDecimal amount) {
    return new AlertMark(match.doubleHit() ? "NAME_ACCOUNT" : "NAME",
      match.target().library(), match.target().libraryLabel(), match.target().name(),
      match.nameMatched(), match.accountMatched(),
      hint(match, date, direction, amount));
  }

  private String hint(Match match, LocalDate date, String direction, BigDecimal amount) {
    StringBuilder sb = new StringBuilder();
    if (match.doubleHit()) {
      sb.append("与").append(shortLabel(match.target().library()))
        .append("「").append(match.target().name()).append("」有往来（名称与卡号一致）");
    } else if (match.nameMatched()) {
      sb.append("与疑似").append(shortLabel(match.target().library()))
        .append("「").append(match.target().name()).append("」有往来");
    } else {
      sb.append("与疑似").append(shortLabel(match.target().library()))
        .append("「").append(match.target().name()).append("」有往来（卡号一致）");
    }
    if (date != null) {
      sb.append("，").append(date);
    }
    if (direction != null && !direction.isBlank()) {
      sb.append(" ").append("入".equals(direction) ? "入账" : "出".equals(direction) ? "出账" : direction);
    }
    if (amount != null) {
      sb.append(" ").append(String.format(Locale.CHINA, "%,.2f", amount.abs())).append(" 元");
    }
    return sb.toString();
  }

  private String shortLabel(String library) {
    return switch (library) {
      case LIB_FUNDER -> "资方";
      case LIB_BLACKLIST_CP, LIB_BLACKLIST_CUSTOMER -> "黑名单";
      default -> "";
    };
  }

  // ==================== 归一化 ====================

  /**
   * 名称归一：全半角统一（NFKC）+ 去所有空白 + 小写。
   */
  static String riskKey(String raw) {
    if (raw == null) {
      return "";
    }
    String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
    StringBuilder sb = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
        continue;
      }
      sb.append(c);
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  /**
   * 卡号归一：全半角统一后只保留数字。
   */
  static String digitsOnly(String raw) {
    if (raw == null) {
      return "";
    }
    String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
    StringBuilder sb = new StringBuilder(normalized.length());
    for (int i = 0; i < normalized.length(); i++) {
      char c = normalized.charAt(i);
      if (Character.isDigit(c)) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String cleanName(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replaceAll("[\\s\\u3000]+", " ").trim();
  }

  // ==================== 内部类型 ====================

  private record Target(String library, String libraryLabel, String name, String nameKey,
                        Set<String> accounts) {
  }

  private record Match(Target target, boolean nameMatched, boolean accountMatched) {
    boolean doubleHit() {
      return nameMatched && accountMatched;
    }
  }

  private static final Comparator<Match> MATCH_ORDER = Comparator
    .comparingInt((Match m) -> m.doubleHit() ? 0 : 1)
    .thenComparingInt(m -> LIB_PRIORITY.getOrDefault(m.target().library(), 9));

  private static final class Index {
    private final List<Target> targets;

    private Index(List<Target> targets) {
      this.targets = targets;
    }

    private Match match(String rawName, String rawAccount) {
      String key = riskKey(rawName);
      String digits = digitsOnly(rawAccount);
      if (key.isEmpty() && digits.isEmpty()) {
        return null;
      }
      Match best = null;
      for (Target target : targets) {
        boolean nameHit = !key.isEmpty() && target.nameKey().equals(key);
        boolean accountHit = !digits.isEmpty() && target.accounts().contains(digits);
        if (!nameHit && !accountHit) {
          continue;
        }
        Match current = new Match(target, nameHit, accountHit);
        if (best == null || MATCH_ORDER.compare(current, best) < 0) {
          best = current;
        }
      }
      return best;
    }
  }

  private static final class CpInfo {
    private String display;
    private final Set<String> accounts = new LinkedHashSet<>();
  }

  private static final class CustomerAgg {
    private String name;
    private final Set<String> accounts = new LinkedHashSet<>();
  }

  private static final class GroupAgg {
    private final Target target;
    private final List<AlertRow> rows = new ArrayList<>();
    private int count;
    private BigDecimal totalAmount = BigDecimal.ZERO;
    private boolean truncated;
    private boolean doubleHit;

    private GroupAgg(Target target) {
      this.target = target;
    }
  }

  // ==================== DTO ====================

  public record AlertRequest(String scope, Long statementId, String accountHolder, List<Item> items) {
  }

  public record Item(String key, String name, String account, LocalDate date,
                     String direction, BigDecimal amount) {
  }

  public record AlertResult(Map<String, AlertMark> marks, AlertSummary summary) {
  }

  public record AlertMark(String matchType, String library, String libraryLabel, String targetName,
                          boolean nameMatched, boolean accountMatched, String hint) {
  }

  public record AlertSummary(int totalCount, int targetCount, BigDecimal totalAmount,
                             List<AlertGroup> groups) {
  }

  public record AlertGroup(String targetName, String library, String libraryLabel, String matchType,
                           int count, BigDecimal totalAmount, boolean rowsTruncated,
                           List<AlertRow> rows) {
  }

  public record AlertRow(Long transactionId, Long statementId, LocalDate transDate, String direction,
                         BigDecimal amount, String counterpartyName, String counterpartyAccount,
                         String matchType, String library, String libraryLabel, String targetName,
                         String hint) {
  }
}
