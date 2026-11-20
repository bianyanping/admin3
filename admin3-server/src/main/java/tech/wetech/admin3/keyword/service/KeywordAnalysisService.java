package tech.wetech.admin3.keyword.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.keyword.model.KeywordGroup;
import tech.wetech.admin3.keyword.repository.KeywordGroupRepository;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.repository.BankStatementRepository;
import tech.wetech.admin3.ocrApi.service.BankStatementScopeService;
import tech.wetech.admin3.ocrApi.service.TransactionLiteQueryService;
import tech.wetech.admin3.ocrApi.service.TransactionLiteQueryService.TxnLite;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 关键词分组维护与命中分析。
 *
 * <p>匹配口径：在分组的匹配字段（备注/摘要）中命中任一关键词，且交易方向在分组的方向范围内。
 */
@Service
public class KeywordAnalysisService {

  private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

  private final KeywordGroupRepository groupRepository;
  private final BankStatementRepository statementRepository;
  private final TransactionLiteQueryService transactionLiteQueryService;
  private final BankStatementScopeService scopeService;

  public KeywordAnalysisService(KeywordGroupRepository groupRepository,
                                BankStatementRepository statementRepository,
                                TransactionLiteQueryService transactionLiteQueryService,
                                BankStatementScopeService scopeService) {
    this.groupRepository = groupRepository;
    this.statementRepository = statementRepository;
    this.transactionLiteQueryService = transactionLiteQueryService;
    this.scopeService = scopeService;
  }

  /**
   * 为指定用户初始化两个预置分组；该用户已有分组时跳过。预置分组按用户各自一份，互不影响。
   */
  @Transactional
  public void ensurePresets(Long userId) {
    if (userId == null || groupRepository.countByOwnerUserId(userId) > 0) {
      return;
    }
    ensurePreset(userId, "借款/还款", "借款,还款", "remark,summary", "ALL",
      "借款、还款类往来，含银行贷款还款");
    ensurePreset(userId, "工资/货款", "工资,货款", "remark,summary", "IN",
      "入账中的工资、货款类收入");
  }

  private void ensurePreset(Long userId, String name, String keywords, String fields, String direction,
                            String remark) {
    if (groupRepository.findByNameAndOwnerUserId(name, userId).isPresent()) {
      return;
    }
    KeywordGroup group = new KeywordGroup();
    group.setOwnerUserId(userId);
    group.setName(name);
    group.setKeywords(keywords);
    group.setFields(fields);
    group.setDirection(direction);
    group.setEnabled(Boolean.TRUE);
    group.setBuiltin(Boolean.TRUE);
    group.setRemark(remark);
    groupRepository.save(group);
  }

  /**
   * 分组列表：普通用户仅自己的分组；管理员看全部并可管理。
   */
  public List<KeywordGroup> listGroups(Long userId) {
    ensurePresets(userId);
    if (scopeService.isAdmin(userId)) {
      return groupRepository.findAllByOrderByIdAsc();
    }
    return groupRepository.findAllByOwnerUserIdOrderByIdAsc(userId);
  }

  /**
   * 参与分析的分组：始终使用当前用户自己的启用分组，管理员也不会混入其他用户的分组。
   */
  public List<KeywordGroup> listEnabledGroups(Long userId) {
    ensurePresets(userId);
    return groupRepository.findAllByOwnerUserIdOrderByIdAsc(userId).stream()
      .filter(g -> Boolean.TRUE.equals(g.getEnabled()))
      .toList();
  }

  public KeywordGroup getGroup(Long id, Long userId) {
    KeywordGroup group = groupRepository.findById(id)
      .orElseThrow(() -> new IllegalArgumentException("关键词分组不存在"));
    if (!scopeService.isAdmin(userId)
      && (userId == null || !userId.equals(group.getOwnerUserId()))) {
      throw new IllegalArgumentException("无权操作他人创建的关键词分组");
    }
    return group;
  }

  @Transactional
  public KeywordGroup createGroup(KeywordGroup source, Long userId) {
    groupRepository.findByNameAndOwnerUserId(source.getName(), userId).ifPresent(g -> {
      throw new IllegalArgumentException("分组名称已存在");
    });
    KeywordGroup group = new KeywordGroup();
    applyGroup(group, source);
    group.setOwnerUserId(userId);
    group.setBuiltin(Boolean.FALSE);
    return groupRepository.save(group);
  }

  @Transactional
  public KeywordGroup updateGroup(Long id, KeywordGroup source, Long userId) {
    KeywordGroup group = getGroup(id, userId);
    groupRepository.findByNameAndOwnerUserId(source.getName(), group.getOwnerUserId()).ifPresent(existing -> {
      if (!existing.getId().equals(id)) {
        throw new IllegalArgumentException("分组名称已存在");
      }
    });
    applyGroup(group, source);
    return groupRepository.save(group);
  }

  private void applyGroup(KeywordGroup target, KeywordGroup source) {
    target.setName(source.getName() == null ? "" : source.getName().trim());
    target.setKeywords(source.getKeywords() == null ? "" : source.getKeywords().trim());
    target.setFields(source.getFields() == null || source.getFields().isBlank() ? "remark,summary" : source.getFields());
    String direction = source.getDirection();
    target.setDirection(direction == null || direction.isBlank() ? "ALL" : direction);
    target.setEnabled(source.getEnabled() == null ? Boolean.TRUE : source.getEnabled());
    target.setRemark(source.getRemark());
  }

  @Transactional
  public KeywordGroup setEnabled(Long id, boolean enabled, Long userId) {
    KeywordGroup group = getGroup(id, userId);
    group.setEnabled(enabled);
    return groupRepository.save(group);
  }

  @Transactional
  public void deleteGroup(Long id, Long userId) {
    KeywordGroup group = getGroup(id, userId);
    if (Boolean.TRUE.equals(group.getBuiltin())) {
      throw new IllegalArgumentException("预置分组不可删除，可停用");
    }
    groupRepository.delete(group);
  }

  /**
   * 按当前可见范围（可再按户名收窄）计算各启用分组的命中情况。
   */
  public AnalysisResult analyze(Long userId, String accountHolder) {
    List<BankStatement> statements = visibleStatements(userId);
    String holderFilter = accountHolder == null ? "" : accountHolder.trim();
    if (!holderFilter.isEmpty()) {
      statements = statements.stream()
        .filter(s -> holderFilter.equals(s.getAccountHolder()))
        .toList();
    }
    List<KeywordGroup> groups = listEnabledGroups(userId);
    if (statements.isEmpty() || groups.isEmpty()) {
      return new AnalysisResult(holderFilter, 0, List.of());
    }

    Map<Long, BankStatement> statementById = new HashMap<>();
    for (BankStatement s : statements) {
      statementById.put(s.getId(), s);
    }
    List<Long> ids = statements.stream().map(BankStatement::getId).toList();
    List<TxnLite> txns = transactionLiteQueryService.findByStatementIds(ids);

    int totalScanned = 0;
    for (TxnLite t : txns) {
      if (isFlow(t)) {
        totalScanned++;
      }
    }

    List<GroupResult> results = new ArrayList<>();
    for (KeywordGroup group : groups) {
      results.add(analyzeGroup(group, txns, statementById, totalScanned));
    }
    return new AnalysisResult(holderFilter, totalScanned, results);
  }

  private GroupResult analyzeGroup(KeywordGroup group, List<TxnLite> txns,
                                   Map<Long, BankStatement> statementById, int totalScanned) {
    List<String> keywordList = split(group.getKeywords());
    Set<String> fields = Set.copyOf(split(group.getFields()));
    boolean allowIncome = !"OUT".equals(group.getDirection());
    boolean allowExpense = !"IN".equals(group.getDirection());

    List<TxnRow> details = new ArrayList<>();
    Map<String, KwAcc> keywordStats = new LinkedHashMap<>();
    for (String keyword : keywordList) {
      keywordStats.put(keyword, new KwAcc());
    }
    Map<YearMonth, MonthAcc> months = new TreeMap<>();

    for (TxnLite t : txns) {
      if (!isFlow(t)) {
        continue;
      }
      boolean income = "入".equals(t.direction());
      if (income && !allowIncome) {
        continue;
      }
      if (!income && !allowExpense) {
        continue;
      }
      List<String> matched = matchKeywords(t, keywordList, fields);
      if (matched.isEmpty()) {
        continue;
      }
      BankStatement statement = statementById.get(t.statementId());
      BigDecimal amount = t.amount() == null ? BigDecimal.ZERO : t.amount().abs();
      BigDecimal signed = income ? amount : amount.negate();
      details.add(new TxnRow(t.id(), statement == null ? null : statement.getId(), t.transDate(),
        t.direction(), signed, t.counterpartyName(), t.summary(), t.remark(),
        statement == null ? null : statement.getBankName(),
        statement == null ? null : statement.getAccountNo(),
        statement == null ? null : statement.getAccountHolder(), matched));

      for (String keyword : matched) {
        KwAcc stat = keywordStats.get(keyword);
        if (stat != null) {
          stat.count++;
          stat.amount = stat.amount.add(amount);
        }
      }
      if (income && t.transDate() != null) {
        YearMonth month = YearMonth.from(t.transDate());
        MonthAcc acc = months.computeIfAbsent(month, m -> new MonthAcc());
        acc.count++;
        acc.amount = acc.amount.add(amount);
        for (String keyword : matched) {
          KwAcc stat = acc.keywords.computeIfAbsent(keyword, k -> new KwAcc());
          stat.count++;
          stat.amount = stat.amount.add(amount);
        }
      }
    }

    details.sort(Comparator.comparing((TxnRow row) ->
        row.counterpartyName() == null ? "" : row.counterpartyName().trim())
      .thenComparing(TxnRow::transDate, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(TxnRow::id));

    List<KeywordStat> stats = keywordList.stream()
      .map(keyword -> {
        KwAcc acc = keywordStats.get(keyword);
        return new KeywordStat(keyword, acc == null ? 0 : acc.count,
          acc == null ? BigDecimal.ZERO : acc.amount);
      })
      .toList();

    BigDecimal hitAmount = BigDecimal.ZERO;
    for (TxnRow row : details) {
      hitAmount = hitAmount.add(row.amount().abs());
    }
    double hitRate = totalScanned == 0 ? 0
      : BigDecimal.valueOf(details.size() * 100.0 / totalScanned)
      .setScale(2, RoundingMode.HALF_UP).doubleValue();

    List<MonthRow> monthRows = months.entrySet().stream()
      .map(entry -> new MonthRow(entry.getKey().format(MONTH_FORMAT), entry.getValue().amount,
        entry.getValue().count,
        entry.getValue().keywords.entrySet().stream()
          .map(e -> new KeywordStat(e.getKey(), e.getValue().count, e.getValue().amount))
          .toList()))
      .toList();

    return new GroupResult(group.getId(), group.getName(), group.getKeywords(), group.getFields(),
      keywordList, group.getDirection(), details.size(), hitRate, hitAmount, stats, details, monthRows);
  }

  private List<String> matchKeywords(TxnLite t, List<String> keywords, Set<String> fields) {
    StringBuilder haystack = new StringBuilder();
    if (fields.contains("remark") && t.remark() != null) {
      haystack.append(t.remark().toLowerCase(Locale.ROOT)).append('\u0001');
    }
    if (fields.contains("summary") && t.summary() != null) {
      haystack.append(t.summary().toLowerCase(Locale.ROOT)).append('\u0001');
    }
    if (haystack.isEmpty()) {
      return List.of();
    }
    String text = haystack.toString();
    List<String> matched = new ArrayList<>();
    for (String keyword : keywords) {
      if (!keyword.isEmpty() && text.contains(keyword)) {
        matched.add(keyword);
      }
    }
    return matched;
  }

  private static List<String> split(String value) {
    List<String> items = new ArrayList<>();
    if (value == null) {
      return items;
    }
    for (String part : value.split("[,，;；\\s]+")) {
      String item = part.trim().toLowerCase(Locale.ROOT);
      if (!item.isEmpty() && !items.contains(item)) {
        items.add(item);
      }
    }
    return items;
  }

  private static boolean isFlow(TxnLite t) {
    return "入".equals(t.direction()) || "出".equals(t.direction());
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
      return visible.isEmpty() ? cb.disjunction() : cb.or(visible.toArray(new Predicate[0]));
    };
    return statementRepository.findAll(spec);
  }

  private static final class MonthAcc {
    private long count;
    private BigDecimal amount = BigDecimal.ZERO;
    private final Map<String, KwAcc> keywords = new LinkedHashMap<>();
  }

  private static final class KwAcc {
    private long count;
    private BigDecimal amount = BigDecimal.ZERO;
  }

  public record AnalysisResult(String scopeHolder, int totalScanned, List<GroupResult> groups) {
  }

  public record GroupResult(Long groupId, String name, String keywords, String fields,
                            List<String> keywordList, String direction,
                            long hitCount, double hitRate, BigDecimal hitAmount,
                            List<KeywordStat> keywordStats, List<TxnRow> details, List<MonthRow> months) {
  }

  public record KeywordStat(String keyword, long count, BigDecimal amount) {
  }

  public record TxnRow(Long id, Long statementId, java.time.LocalDate transDate, String direction,
                       BigDecimal amount, String counterpartyName, String summary, String remark,
                       String bankName, String accountNo, String accountHolder,
                       List<String> matchedKeywords) {
  }

  public record MonthRow(String month, BigDecimal incomeAmount, long incomeCount,
                         List<KeywordStat> keywordStats) {
  }
}
