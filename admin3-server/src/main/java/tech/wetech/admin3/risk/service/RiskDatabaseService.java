package tech.wetech.admin3.risk.service;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.common.scope.OrgScopeService;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.model.BankStatementTransaction;
import tech.wetech.admin3.ocrApi.model.CreditReport;
import tech.wetech.admin3.ocrApi.repository.BankStatementRepository;
import tech.wetech.admin3.ocrApi.repository.BankStatementTransactionRepository;
import tech.wetech.admin3.ocrApi.repository.CreditReportRepository;
import tech.wetech.admin3.risk.model.RiskCounterparty;
import tech.wetech.admin3.risk.model.RiskExclusionRule;
import tech.wetech.admin3.risk.model.RiskTag;
import tech.wetech.admin3.risk.model.RiskTagLink;
import tech.wetech.admin3.risk.repository.RiskCounterpartyRepository;
import tech.wetech.admin3.risk.repository.RiskExclusionRuleRepository;
import tech.wetech.admin3.risk.repository.RiskTagLinkRepository;
import tech.wetech.admin3.risk.repository.RiskTagRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 风险数据库：交易对手沉淀、消费型对手剔除、客户聚合与标签挂载。
 *
 * <p>数据来源为当前用户可见范围内的流水；对手主数据在首次访问时重建，
 * 10 分钟内复用，剔除规则变更后标记为待重建。
 */
@Service
public class RiskDatabaseService {

  private static final Logger log = LoggerFactory.getLogger(RiskDatabaseService.class);

  /** 对手沉淀缓存有效期 */
  private static final Duration SYNC_TTL = Duration.ofMinutes(10);

  private final RiskCounterpartyRepository counterpartyRepository;
  private final RiskTagRepository tagRepository;
  private final RiskTagLinkRepository tagLinkRepository;
  private final RiskExclusionRuleRepository exclusionRuleRepository;
  private final BankStatementRepository statementRepository;
  private final BankStatementTransactionRepository transactionRepository;
  private final CreditReportRepository creditReportRepository;
  private final OrgScopeService orgScope;
  private final TransactionTemplate transactionTemplate;
  private final JdbcTemplate jdbcTemplate;

  private final Map<Long, LocalDateTime> lastSyncByOrg = new ConcurrentHashMap<>();
  /** 按组织串行化重建，避免同组织并发 delete+insert */
  private final ConcurrentHashMap<Long, ReentrantLock> orgLocks = new ConcurrentHashMap<>();

  public RiskDatabaseService(RiskCounterpartyRepository counterpartyRepository,
                             RiskTagRepository tagRepository,
                             RiskTagLinkRepository tagLinkRepository,
                             RiskExclusionRuleRepository exclusionRuleRepository,
                             BankStatementRepository statementRepository,
                             BankStatementTransactionRepository transactionRepository,
                             CreditReportRepository creditReportRepository,
                             OrgScopeService orgScope,
                             PlatformTransactionManager transactionManager,
                             JdbcTemplate jdbcTemplate) {
    this.counterpartyRepository = counterpartyRepository;
    this.tagRepository = tagRepository;
    this.tagLinkRepository = tagLinkRepository;
    this.exclusionRuleRepository = exclusionRuleRepository;
    this.statementRepository = statementRepository;
    this.transactionRepository = transactionRepository;
    this.creditReportRepository = creditReportRepository;
    this.orgScope = orgScope;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.jdbcTemplate = jdbcTemplate;
  }

  // ==================== 预置数据 ====================

  /**
   * 保证对手名称唯一键使用二进制排序规则。
   *
   * <p>应用层已对名称做过 normalize（去空白 + 小写），若数据库继续用默认的 {@code *_ai_ci} 排序规则，
   * 会把 {@code *a} 与 {@code *ᵃ}、全角与半角等不同名称判定为相等，批量插入时就会撞唯一键。
   * 只在排序规则不一致时执行一次 ALTER，已正确时直接跳过。
   */
  public void ensureSchema() {
    try {
      Integer mismatched = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
          + "AND table_name = 'risk_counterparty' AND column_name = 'name_key' "
          + "AND (collation_name IS NULL OR collation_name <> 'utf8mb4_bin')", Integer.class);
      if (mismatched == null || mismatched == 0) {
        return;
      }
      jdbcTemplate.execute("ALTER TABLE risk_counterparty MODIFY name_key VARCHAR(255) "
        + "CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL");
      log.info("risk_counterparty.name_key 已切换为 utf8mb4_bin 排序规则");
    } catch (RuntimeException e) {
      log.warn("risk_counterparty.name_key 排序规则调整失败：{}", e.getMessage());
    }
  }


  /**
   * 预置标签定义与消费型剔除规则，已存在则跳过。
   */
  @Transactional
  public void ensurePresets() {
    ensureTag("FUNDER", "民间资方", RiskTag.SUBJECT_COUNTERPARTY, "warning",
      "手工标记的民间资方/出资人", 10);
    ensureTag("BLACKLIST_CP", "黑名单对手", RiskTag.SUBJECT_COUNTERPARTY, "danger",
      "手工标记的高风险交易对手", 20);
    ensureTag("CONSUMER", "消费型大众对手", RiskTag.SUBJECT_COUNTERPARTY, "info",
      "系统规则自动标记：抖音、美团、淘宝、京东等消费平台", 90);
    ensureTag("BLACKLIST_CUSTOMER", "黑名单客户", RiskTag.SUBJECT_CUSTOMER, "danger",
      "手工标记的黑名单客户/户主", 10);

    // 支付类中性名称收窄为完全匹配，避免「支付宝支付科技有限公司」「财付通支付科技有限公司」被包含规则误剔
    alignBuiltinRule("支付宝", RiskExclusionRule.MATCH_EXACT, "支付");
    alignBuiltinRule("微信支付", RiskExclusionRule.MATCH_EXACT, "支付");
    alignBuiltinRule("财付通", RiskExclusionRule.MATCH_EXACT, "支付");
    ensureRule("抖音", RiskExclusionRule.MATCH_CONTAINS, "短视频/电商");
    ensureRule("美团", RiskExclusionRule.MATCH_CONTAINS, "外卖/本地生活");
    ensureRule("拼多多", RiskExclusionRule.MATCH_CONTAINS, "电商");
    ensureRule("淘宝", RiskExclusionRule.MATCH_CONTAINS, "电商");
    ensureRule("天猫", RiskExclusionRule.MATCH_CONTAINS, "电商");
    ensureRule("支付宝", RiskExclusionRule.MATCH_EXACT, "支付");
    ensureRule("微信", RiskExclusionRule.MATCH_CONTAINS, "支付");
    // 微信平台中性名称显式覆盖；公司主体由 rebuild 时的公司保护逻辑跳过，不会被误剔除
    ensureRule("微信转账", RiskExclusionRule.MATCH_CONTAINS, "支付");
    ensureRule("微信红包", RiskExclusionRule.MATCH_CONTAINS, "支付");
    ensureRule("微信零钱", RiskExclusionRule.MATCH_CONTAINS, "支付");
    ensureRule("微信支付", RiskExclusionRule.MATCH_EXACT, "支付");
    ensureRule("饿了么", RiskExclusionRule.MATCH_CONTAINS, "外卖");
    ensureRule("财付通", RiskExclusionRule.MATCH_EXACT, "支付");
    ensureRule("快手", RiskExclusionRule.MATCH_CONTAINS, "短视频/电商");
    ensureRule("滴滴", RiskExclusionRule.MATCH_CONTAINS, "出行");
    ensureRule("唯品会", RiskExclusionRule.MATCH_CONTAINS, "电商");
    ensureRule("苏宁", RiskExclusionRule.MATCH_CONTAINS, "电商");
    ensureRule("盒马", RiskExclusionRule.MATCH_CONTAINS, "商超");
    // 京东单独用完全匹配：剔除「京东」，保留「京东物流运输有限公司」等公司主体
    ensureRule("京东", RiskExclusionRule.MATCH_EXACT, "电商");
    ensureRule("京东商城", RiskExclusionRule.MATCH_CONTAINS, "电商");
    ensureRule("京东支付", RiskExclusionRule.MATCH_CONTAINS, "支付");
    ensureRule("京东快递", RiskExclusionRule.MATCH_CONTAINS, "物流");
  }

  private void ensureTag(String code, String name, String subjectType, String color,
                         String description, int sortOrder) {
    if (tagRepository.findByCode(code).isPresent()) {
      return;
    }
    RiskTag tag = new RiskTag();
    tag.setCode(code);
    tag.setName(name);
    tag.setSubjectType(subjectType);
    tag.setColor(color);
    tag.setBuiltin(Boolean.TRUE);
    tag.setDescription(description);
    tag.setSortOrder(sortOrder);
    tagRepository.save(tag);
  }

  private void ensureRule(String keyword, String matchType, String category) {
    if (exclusionRuleRepository.findByKeywordAndMatchType(keyword, matchType).isPresent()) {
      return;
    }
    RiskExclusionRule rule = new RiskExclusionRule();
    rule.setKeyword(keyword);
    rule.setMatchType(matchType);
    rule.setCategory(category);
    rule.setEnabled(Boolean.TRUE);
    rule.setBuiltin(Boolean.TRUE);
    exclusionRuleRepository.save(rule);
  }

  /**
   * 纠正历史内置规则的匹配方式（仅内置规则）：已存在目标匹配方式时删除重复的旧规则，
   * 否则将旧内置规则更新为目标匹配方式；用户自建规则不受影响。
   */
  private void alignBuiltinRule(String keyword, String matchType, String category) {
    List<RiskExclusionRule> rules = exclusionRuleRepository.findByKeyword(keyword);
    boolean targetExists = rules.stream()
      .anyMatch(rule -> Boolean.TRUE.equals(rule.getBuiltin()) && matchType.equals(rule.getMatchType()));
    for (RiskExclusionRule rule : rules) {
      if (!Boolean.TRUE.equals(rule.getBuiltin()) || matchType.equals(rule.getMatchType())) {
        continue;
      }
      if (targetExists) {
        exclusionRuleRepository.delete(rule);
      } else {
        rule.setMatchType(matchType);
        rule.setCategory(category);
        exclusionRuleRepository.save(rule);
        targetExists = true;
      }
    }
  }

  // ==================== 对手沉淀 ====================

  /**
   * 按需同步：每个组织独立缓存，缓存有效且该组织已有数据时直接返回，否则只重建该组织的对手。
   * 数据源严格取本组织子树内的流水，不因单条流水分享跨组织扩散。
   */
  public SyncResult syncIfNeeded(Long userId, boolean force) {
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      return emptySyncResult();
    }
    ReentrantLock lock = orgLocks.computeIfAbsent(orgId, key -> new ReentrantLock());
    lock.lock();
    try {
      LocalDateTime lastSync = lastSyncByOrg.get(orgId);
      if (!force && lastSync != null && !lastSync.plus(SYNC_TTL).isBefore(LocalDateTime.now())
        && counterpartyRepository.countByOrgId(orgId) > 0) {
        return buildSyncResult(orgId);
      }
      return rebuild(orgId);
    } finally {
      lock.unlock();
    }
  }

  /**
   * 重建当前组织的对手沉淀。
   *
   * <p>并发保护：JVM 内按组织加锁，跨实例再加 MySQL 命名锁；
   * 命名锁与事务共用同一个连接，在事务结束（提交/回滚）后释放，避免锁先释放导致其他实例读到中间状态。
   *
   * <p>若仍遇到唯一键冲突（并发写入或历史脏数据），整个事务已回滚，重新删旧+插入一次。
   */
  private SyncResult rebuild(Long orgId) {
    String lockName = "risk:counterparty:org:" + orgId;
    DataIntegrityViolationException last = null;
    for (int attempt = 1; attempt <= 3; attempt++) {
      try {
        SyncResult result = transactionTemplate.execute(status -> {
          if (!acquireDbLock(lockName, 60)) {
            throw new BusinessException(CommonResultStatus.FAIL, "交易对手数据正在重建，请稍后重试");
          }
          TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
              releaseDbLock(lockName);
            }
          });
          return doRebuild(orgId);
        });
        lastSyncByOrg.put(orgId, LocalDateTime.now());
        return result == null ? emptySyncResult() : result;
      } catch (DataIntegrityViolationException e) {
        last = e;
        try {
          Thread.sleep(50L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    if (last != null) {
      throw last;
    }
    throw new BusinessException(CommonResultStatus.FAIL, "交易对手数据重建失败，请稍后重试");
  }

  private boolean acquireDbLock(String lockName, int timeoutSeconds) {
    Integer acquired = jdbcTemplate.queryForObject(
      "SELECT GET_LOCK(?, ?)", Integer.class, lockName, timeoutSeconds);
    return acquired != null && acquired == 1;
  }

  private void releaseDbLock(String lockName) {
    try {
      jdbcTemplate.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, lockName);
    } catch (RuntimeException ignored) {
      // 连接关闭时 MySQL 会自动释放命名锁
    }
  }

  private SyncResult doRebuild(Long orgId) {
    List<BankStatement> statements = statementsInOrgTree(orgId);
    Map<Long, String> holderByStatement = new HashMap<>();
    for (BankStatement s : statements) {
      holderByStatement.put(s.getId(), displayName(s.getAccountHolder()));
    }
    List<BankStatementTransaction> txns = loadTransactions(statements);
    List<RiskExclusionRule> rules = exclusionRuleRepository.findByEnabledTrueOrderByIdAsc();

    Map<String, CpAgg> groups = new HashMap<>();
    for (BankStatementTransaction t : txns) {
      String raw = displayName(t.getCounterpartyName());
      String key = normalize(raw);
      if (key.isEmpty()) {
        continue;
      }
      CpAgg agg = groups.computeIfAbsent(key, k -> new CpAgg());
      agg.displayCounts.merge(raw, 1, Integer::sum);
      if (t.getCounterpartyAccount() != null && !t.getCounterpartyAccount().isBlank()) {
        agg.accounts.add(t.getCounterpartyAccount().trim());
      }
      agg.txnCount++;
      BigDecimal amount = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount().abs();
      if ("入".equals(t.getDirection())) {
        agg.incomeCount++;
        agg.incomeTotal = agg.incomeTotal.add(amount);
      } else if ("出".equals(t.getDirection())) {
        agg.expenseCount++;
        agg.expenseTotal = agg.expenseTotal.add(amount);
      }
      if (t.getTransDate() != null) {
        if (agg.firstDate == null || t.getTransDate().isBefore(agg.firstDate)) {
          agg.firstDate = t.getTransDate();
        }
        if (agg.lastDate == null || t.getTransDate().isAfter(agg.lastDate)) {
          agg.lastDate = t.getTransDate();
        }
      }
      String holder = holderByStatement.get(t.getStatement().getId());
      if (holder != null && !holder.isBlank()) {
        agg.holders.add(holder);
      }
    }

    // 只重建当前组织的数据，避免其他组织重建时互相覆盖
    counterpartyRepository.deleteByOrgId(orgId);
    List<RiskCounterparty> entities = new ArrayList<>(groups.size());
    LocalDateTime now = LocalDateTime.now();
    long consumerTxn = 0;
    for (Map.Entry<String, CpAgg> entry : groups.entrySet()) {
      CpAgg agg = entry.getValue();
      List<String> matched = matchRules(entry.getKey(), rules);
      // 公司/集团/银行等公司主体不判为消费型，避免包含匹配误伤对公客户
      boolean companyEntity = isCompanyEntity(mostFrequent(agg.displayCounts));
      boolean consumer = !matched.isEmpty() && !companyEntity;
      if (consumer) {
        consumerTxn += agg.txnCount;
      }
      RiskCounterparty entity = new RiskCounterparty();
      entity.setOrgId(orgId);
      entity.setNameKey(entry.getKey());
      entity.setDisplayName(mostFrequent(agg.displayCounts));
      entity.setAccountNos(String.join(",", agg.accounts));
      entity.setAccountCount(agg.accounts.size());
      entity.setTxnCount(agg.txnCount);
      entity.setIncomeCount(agg.incomeCount);
      entity.setIncomeTotal(agg.incomeTotal);
      entity.setExpenseCount(agg.expenseCount);
      entity.setExpenseTotal(agg.expenseTotal);
      entity.setHolderCount(agg.holders.size());
      entity.setFirstDate(agg.firstDate);
      entity.setLastDate(agg.lastDate);
      entity.setConsumer(consumer);
      entity.setConsumerRules(consumer ? String.join(",", matched) : null);
      entity.setSyncTime(now);
      entities.add(entity);
    }
    counterpartyRepository.saveAll(entities);
    syncSystemTag();
    return new SyncResult(entities.size(), (int) entities.stream().filter(RiskCounterparty::getConsumer).count(),
      (int) entities.stream().filter(e -> !e.getConsumer()).count(), consumerTxn, now,
      buildRuleStats(groups, rules), consumerSamples(entities));
  }

  /**
   * 系统规则标签：消费型对手自动挂「消费型大众对手」标签，来源=SYSTEM、全局可见。
   *
   * <p>消费型判定只依赖名称与剔除规则，与流水可见范围无关，因此按全部用户的沉淀结果
   * 统一维护一份系统标签，避免某个用户重建时把其他人的系统标签清掉。
   */
  private void syncSystemTag() {
    RiskTag tag = tagRepository.findByCode("CONSUMER").orElse(null);
    if (tag == null) {
      return;
    }
    Set<String> consumerKeys = counterpartyRepository.findByConsumer(Boolean.TRUE).stream()
      .map(RiskCounterparty::getNameKey)
      .collect(Collectors.toSet());
    Map<String, RiskTagLink> existing = tagLinkRepository
      .findByTagIdAndSource(tag.getId(), RiskTagLink.SOURCE_SYSTEM).stream()
      .collect(Collectors.toMap(RiskTagLink::getSubjectKey, Function.identity(), (a, b) -> a));
    for (RiskTagLink link : existing.values()) {
      if (!consumerKeys.contains(link.getSubjectKey())) {
        tagLinkRepository.delete(link);
      }
    }
    List<RiskTagLink> links = new ArrayList<>();
    for (String key : consumerKeys) {
      if (existing.containsKey(key)) {
        continue;
      }
      RiskTagLink link = new RiskTagLink();
      link.setSubjectType(RiskTag.SUBJECT_COUNTERPARTY);
      link.setSubjectKey(key);
      link.setTagId(tag.getId());
      link.setSource(RiskTagLink.SOURCE_SYSTEM);
      link.setCreateUser("system");
      links.add(link);
    }
    if (!links.isEmpty()) {
      tagLinkRepository.saveAll(links);
    }
  }

  private SyncResult buildSyncResult(Long orgId) {
    List<RiskCounterparty> all = counterpartyRepository.findByOrgId(orgId);
    long consumerTxn = all.stream().filter(RiskCounterparty::getConsumer)
      .mapToLong(e -> e.getTxnCount() == null ? 0 : e.getTxnCount()).sum();
    List<RiskExclusionRule> rules = exclusionRuleRepository.findByEnabledTrueOrderByIdAsc();
    Map<String, CpAgg> groups = new HashMap<>();
    for (RiskCounterparty e : all) {
      CpAgg agg = new CpAgg();
      agg.txnCount = e.getTxnCount() == null ? 0 : e.getTxnCount();
      groups.put(e.getNameKey(), agg);
    }
    LocalDateTime lastSync = lastSyncByOrg.get(orgId);
    return new SyncResult(all.size(), (int) all.stream().filter(RiskCounterparty::getConsumer).count(),
      (int) all.stream().filter(e -> !e.getConsumer()).count(), consumerTxn,
      lastSync, buildRuleStats(groups, rules), consumerSamples(all));
  }

  private SyncResult emptySyncResult() {
    return new SyncResult(0, 0, 0, 0, null, List.of(), List.of());
  }

  private List<RuleStat> buildRuleStats(Map<String, CpAgg> groups, List<RiskExclusionRule> rules) {
    List<RuleStat> stats = new ArrayList<>();
    for (RiskExclusionRule rule : rules) {
      String keyword = normalize(rule.getKeyword());
      if (keyword.isEmpty()) {
        continue;
      }
      long count = 0;
      long txnCount = 0;
      for (Map.Entry<String, CpAgg> entry : groups.entrySet()) {
        if (matches(entry.getKey(), keyword, rule.getMatchType())) {
          count++;
          txnCount += entry.getValue().txnCount;
        }
      }
      if (count > 0) {
        stats.add(new RuleStat(rule.getKeyword(), rule.getMatchType(), count, txnCount));
      }
    }
    stats.sort(Comparator.comparingLong(RuleStat::txnCount).reversed());
    return stats;
  }

  private List<String> consumerSamples(List<RiskCounterparty> entities) {
    return entities.stream().filter(RiskCounterparty::getConsumer)
      .sorted(Comparator.comparingInt((RiskCounterparty e) -> e.getTxnCount() == null ? 0 : e.getTxnCount()).reversed())
      .limit(8)
      .map(RiskCounterparty::getDisplayName)
      .toList();
  }

  /**
   * 汇总信息：用于风险数据库页头展示沉淀/剔除规模与剔除规则。
   */
  public Summary summary(Long userId) {
    SyncResult sync = syncIfNeeded(userId, false);
    List<CustomerView> customers = customers(userId, null);
    Set<Long> scopeOrgIds = orgScope.scopeOrgIds(userId);
    RiskTag blacklist = tagRepository.findByCode("BLACKLIST_CUSTOMER").orElse(null);
    RiskTag funder = tagRepository.findByCode("FUNDER").orElse(null);
    int blacklistCount = blacklist == null || scopeOrgIds.isEmpty() ? 0
      : (int) tagLinkRepository.countBySubjectTypeAndTagIdAndSourceAndOrgIdIn(RiskTag.SUBJECT_CUSTOMER,
      blacklist.getId(), RiskTagLink.SOURCE_MANUAL, scopeOrgIds);
    int funderCount = funder == null || scopeOrgIds.isEmpty() ? 0
      : (int) tagLinkRepository.countBySubjectTypeAndTagIdAndSourceAndOrgIdIn(RiskTag.SUBJECT_COUNTERPARTY,
      funder.getId(), RiskTagLink.SOURCE_MANUAL, scopeOrgIds);
    return new Summary(sync.total(), sync.consumerCount(), sync.keptCount(), sync.consumerTxnCount(),
      customers.size(), blacklistCount, funderCount,
      exclusionRuleRepository.findByEnabledTrueOrderByIdAsc().size(),
      sync.syncTime(), sync.rules(), sync.consumerSamples());
  }

  // ==================== 交易对手列表 ====================

  public Page<CounterpartyView> counterparties(Long userId, String keyword, String consumerMode,
                                               String tagCode, int page, int size,
                                               String sortBy, String sortDir) {
    syncIfNeeded(userId, false);
    Long orgId = orgScope.dataOrgId(userId);
    boolean admin = orgScope.isAdmin(userId);
    Set<Long> scopeOrgIds = orgScope.scopeOrgIds(userId);
    RiskTag tag = tagCode == null || tagCode.isBlank() ? null
      : tagRepository.findByCode(tagCode).orElse(null);

    Specification<RiskCounterparty> spec = (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      // 只展示当前组织沉淀的对手（已覆盖本组织子树数据），避免跨组织串数据
      predicates.add(orgId == null ? cb.disjunction() : cb.equal(root.get("orgId"), orgId));
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        String digits = digitsOnly(keyword);
        predicates.add(cb.or(
          cb.like(cb.lower(root.get("nameKey")), like),
          cb.like(cb.lower(root.get("displayName")), like),
          digits.isEmpty() ? cb.disjunction() : cb.like(root.get("accountNos"), "%" + digits + "%")
        ));
      }
      if ("ONLY".equalsIgnoreCase(consumerMode)) {
        predicates.add(cb.isTrue(root.get("consumer")));
      } else if (!"INCLUDE".equalsIgnoreCase(consumerMode)) {
        predicates.add(cb.isFalse(root.get("consumer")));
      }
      if (tag != null) {
        Subquery<String> sub = query.subquery(String.class);
        Root<RiskTagLink> link = sub.from(RiskTagLink.class);
        List<Predicate> linkPredicates = new ArrayList<>();
        linkPredicates.add(cb.equal(link.get("subjectType"), RiskTag.SUBJECT_COUNTERPARTY));
        linkPredicates.add(cb.equal(link.get("tagId"), tag.getId()));
        if (!admin) {
          linkPredicates.add(cb.or(
            cb.equal(link.get("source"), RiskTagLink.SOURCE_SYSTEM),
            scopeOrgIds.isEmpty() ? cb.disjunction()
              : cb.coalesce(link.get("orgId"), OrgScopeService.ROOT_ORG_ID).in(scopeOrgIds)));
        }
        sub.select(link.get("subjectKey")).where(linkPredicates.toArray(new Predicate[0]));
        predicates.add(root.get("nameKey").in(sub));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };

    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 200),
      buildSort(sortBy, sortDir));
    Page<RiskCounterparty> result = counterpartyRepository.findAll(spec, pageable);
    Map<String, List<TagView>> tags = tagsByNames(RiskTag.SUBJECT_COUNTERPARTY,
      result.getContent().stream().map(RiskCounterparty::getNameKey).toList(), userId);
    return result.map(e -> toView(e, tags.getOrDefault(e.getNameKey(), List.of())));
  }

  private Sort buildSort(String sortBy, String sortDir) {
    String field = switch (sortBy == null ? "" : sortBy) {
      case "incomeTotal", "expenseTotal", "lastDate", "displayName", "accountCount", "txnCount" -> sortBy;
      default -> "txnCount";
    };
    Sort.Direction direction = "asc".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, field).and(Sort.by(Sort.Direction.ASC, "id"));
  }

  private CounterpartyView toView(RiskCounterparty e, List<TagView> tags) {
    return new CounterpartyView(e.getId(), e.getNameKey(), e.getDisplayName(), e.getAccountNos(),
      nz(e.getAccountCount()), nz(e.getTxnCount()), nz(e.getIncomeCount()), nz(e.getIncomeTotal()),
      nz(e.getExpenseCount()), nz(e.getExpenseTotal()), nz(e.getHolderCount()),
      e.getFirstDate(), e.getLastDate(), Boolean.TRUE.equals(e.getConsumer()), e.getConsumerRules(), tags);
  }

  // ==================== 客户（黑名单）列表 ====================

  public List<CustomerView> customers(Long userId, String keyword) {
    List<BankStatement> statements = statementsOfCurrentOrgScope(userId);
    Map<String, CustAgg> groups = new TreeMap<>();
    for (BankStatement s : statements) {
      String name = displayName(s.getAccountHolder());
      String key = normalize(name);
      if (key.isEmpty()) {
        continue;
      }
      CustAgg agg = groups.computeIfAbsent(key, k -> new CustAgg());
      agg.name = name;
      agg.statementCount++;
      agg.transactionCount += s.getTransactionCount() == null ? 0 : s.getTransactionCount();
      if (s.getAccountNo() != null && !s.getAccountNo().isBlank()) {
        agg.accounts.add(s.getAccountNo().trim());
      }
      if (s.getTotalIncome() != null) {
        agg.incomeTotal = agg.incomeTotal.add(s.getTotalIncome());
      }
      if (s.getTotalExpense() != null) {
        agg.expenseTotal = agg.expenseTotal.add(s.getTotalExpense());
      }
      if (s.getStartDate() != null && (agg.firstDate == null || s.getStartDate().isBefore(agg.firstDate))) {
        agg.firstDate = s.getStartDate();
      }
      if (s.getEndDate() != null && (agg.lastDate == null || s.getEndDate().isAfter(agg.lastDate))) {
        agg.lastDate = s.getEndDate();
      }
    }

    // 征信报告按姓名归并统计
    Map<String, Integer> creditCounts = new HashMap<>();
    for (CreditReport report : creditReportRepository.findAll()) {
      String key = normalize(report.getName());
      if (!key.isEmpty()) {
        creditCounts.merge(key, 1, Integer::sum);
      }
    }

    Map<String, List<TagView>> tags = tagsByNames(RiskTag.SUBJECT_CUSTOMER, groups.keySet(), userId);
    String filter = normalize(keyword);
    List<CustomerView> views = new ArrayList<>();
    for (Map.Entry<String, CustAgg> entry : groups.entrySet()) {
      if (!filter.isEmpty() && !entry.getKey().contains(filter)) {
        continue;
      }
      CustAgg agg = entry.getValue();
      views.add(new CustomerView(entry.getKey(), agg.name, agg.accounts.size(), agg.statementCount,
        agg.transactionCount, creditCounts.getOrDefault(entry.getKey(), 0),
        agg.incomeTotal, agg.expenseTotal, agg.firstDate, agg.lastDate,
        tags.getOrDefault(entry.getKey(), List.of())));
    }
    views.sort(Comparator.comparingInt(CustomerView::statementCount).reversed()
      .thenComparing(CustomerView::name));
    return views;
  }

  // ==================== 标签 ====================

  public List<TagView> listTags(String subjectType) {
    List<RiskTag> tags = subjectType == null || subjectType.isBlank()
      ? tagRepository.findAllByOrderBySortOrderAscIdAsc()
      : tagRepository.findBySubjectTypeOrderBySortOrderAscIdAsc(subjectType);
    return tags.stream().map(t -> new TagView(t.getId(), t.getCode(), t.getName(), t.getColor(),
      t.getSubjectType(), null, t.getBuiltin(), t.getDescription())).toList();
  }

  /**
   * 批量取标签：系统标签全局可见；手工标签可见本组织及下级组织打的标签，管理员全部。
   */
  public Map<String, List<TagView>> tagsByNames(String subjectType, java.util.Collection<String> names,
                                                 Long userId) {
    List<String> keys = names.stream().map(RiskDatabaseService::normalize)
      .filter(k -> !k.isEmpty()).distinct().toList();
    if (keys.isEmpty()) {
      return Map.of();
    }
    boolean admin = orgScope.isAdmin(userId);
    Set<Long> scopeOrgIds = orgScope.scopeOrgIds(userId);
    Map<Long, RiskTag> tagMap = tagRepository.findAll().stream()
      .collect(Collectors.toMap(RiskTag::getId, Function.identity()));
    Map<String, List<TagView>> result = new LinkedHashMap<>();
    for (RiskTagLink link : tagLinkRepository.findBySubjectTypeAndSubjectKeyIn(subjectType, keys)) {
      if (!tagLinkVisible(link, scopeOrgIds, admin)) {
        continue;
      }
      RiskTag tag = tagMap.get(link.getTagId());
      if (tag == null) {
        continue;
      }
      result.computeIfAbsent(link.getSubjectKey(), k -> new ArrayList<>())
        .add(new TagView(tag.getId(), tag.getCode(), tag.getName(), tag.getColor(),
          tag.getSubjectType(), link.getSource(), tag.getBuiltin(), tag.getDescription()));
    }
    return result;
  }

  /**
   * 标签可见性：系统标签全局可见；手工标签可见本组织及下级组织，管理员全部。
   */
  private boolean tagLinkVisible(RiskTagLink link, Set<Long> scopeOrgIds, boolean admin) {
    if (RiskTagLink.SOURCE_SYSTEM.equals(link.getSource())) {
      return true;
    }
    if (admin) {
      return true;
    }
    return scopeOrgIds.contains(orgScope.effectiveOrgId(link.getOrgId()));
  }

  @Transactional
  public List<TagView> linkTag(String subjectType, String rawSubject, String tagCode, Long userId,
                               String createUser) {
    RiskTag tag = tagRepository.findByCode(tagCode)
      .orElseThrow(() -> new IllegalArgumentException("标签不存在"));
    if (!tag.getSubjectType().equals(subjectType)) {
      throw new IllegalArgumentException("标签主体类型不匹配");
    }
    String key = normalize(rawSubject);
    if (key.isEmpty()) {
      throw new IllegalArgumentException("主体名称不能为空");
    }
    Long orgId = orgScope.writeOrgId();
    RiskTagLink link = tagLinkRepository
      .findBySubjectTypeAndSubjectKeyAndTagIdAndOrgId(subjectType, key, tag.getId(), orgId)
      .orElseGet(() -> {
        RiskTagLink created = new RiskTagLink();
        created.setSubjectType(subjectType);
        created.setSubjectKey(key);
        created.setTagId(tag.getId());
        created.setSource(RiskTagLink.SOURCE_MANUAL);
        created.setOrgId(orgId);
        created.setOwnerUserId(userId);
        created.setCreateUser(createUser);
        return created;
      });
    tagLinkRepository.save(link);
    return tagsByNames(subjectType, List.of(key), userId).getOrDefault(key, List.of());
  }

  /**
   * 取消手工标签：本组织及下级组织的手工标签可取消，管理员可取消全部；系统标签不受影响。
   */
  @Transactional
  public void unlinkTag(String subjectType, String rawSubject, String tagCode, Long userId) {
    RiskTag tag = tagRepository.findByCode(tagCode)
      .orElseThrow(() -> new IllegalArgumentException("标签不存在"));
    String key = normalize(rawSubject);
    boolean admin = orgScope.isAdmin(userId);
    Set<Long> scopeOrgIds = orgScope.scopeOrgIds(userId);
    for (RiskTagLink link : tagLinkRepository.findBySubjectTypeAndSubjectKeyAndTagId(subjectType, key,
      tag.getId())) {
      if (!RiskTagLink.SOURCE_MANUAL.equals(link.getSource())) {
        continue;
      }
      if (admin || scopeOrgIds.contains(orgScope.effectiveOrgId(link.getOrgId()))) {
        tagLinkRepository.delete(link);
      }
    }
  }

  // ==================== 剔除规则 ====================

  public List<RuleView> listRules() {
    return exclusionRuleRepository.findAllByOrderByIdAsc().stream()
      .map(r -> new RuleView(r.getId(), r.getKeyword(), r.getMatchType(), r.getCategory(),
        r.getEnabled(), r.getBuiltin(), r.getRemark()))
      .toList();
  }

  @Transactional
  public RuleView createRule(RiskExclusionRule source) {
    String keyword = source.getKeyword() == null ? "" : source.getKeyword().trim();
    if (keyword.isEmpty()) {
      throw new IllegalArgumentException("关键词不能为空");
    }
    String matchType = RiskExclusionRule.MATCH_EXACT.equals(source.getMatchType())
      ? RiskExclusionRule.MATCH_EXACT : RiskExclusionRule.MATCH_CONTAINS;
    exclusionRuleRepository.findByKeywordAndMatchType(keyword, matchType).ifPresent(r -> {
      throw new IllegalArgumentException("该关键词与匹配方式已存在");
    });
    RiskExclusionRule rule = new RiskExclusionRule();
    rule.setKeyword(keyword);
    rule.setMatchType(matchType);
    applyRule(rule, source);
    rule.setBuiltin(Boolean.FALSE);
    RiskExclusionRule saved = exclusionRuleRepository.save(rule);
    lastSyncByOrg.clear();
    return toRuleView(saved);
  }

  @Transactional
  public RuleView updateRule(Long id, RiskExclusionRule source) {
    RiskExclusionRule rule = exclusionRuleRepository.findById(id)
      .orElseThrow(() -> new IllegalArgumentException("剔除规则不存在"));
    String keyword = source.getKeyword() == null ? "" : source.getKeyword().trim();
    if (keyword.isEmpty()) {
      throw new IllegalArgumentException("关键词不能为空");
    }
    String matchType = RiskExclusionRule.MATCH_EXACT.equals(source.getMatchType())
      ? RiskExclusionRule.MATCH_EXACT : RiskExclusionRule.MATCH_CONTAINS;
    exclusionRuleRepository.findByKeywordAndMatchType(keyword, matchType).ifPresent(existing -> {
      if (!existing.getId().equals(id)) {
        throw new IllegalArgumentException("该关键词与匹配方式已存在");
      }
    });
    rule.setKeyword(keyword);
    rule.setMatchType(matchType);
    applyRule(rule, source);
    RiskExclusionRule saved = exclusionRuleRepository.save(rule);
    lastSyncByOrg.clear();
    return toRuleView(saved);
  }

  private void applyRule(RiskExclusionRule target, RiskExclusionRule source) {
    target.setCategory(source.getCategory());
    target.setEnabled(source.getEnabled() == null ? Boolean.TRUE : source.getEnabled());
    target.setRemark(source.getRemark());
  }

  @Transactional
  public RuleView setRuleEnabled(Long id, boolean enabled) {
    RiskExclusionRule rule = exclusionRuleRepository.findById(id)
      .orElseThrow(() -> new IllegalArgumentException("剔除规则不存在"));
    rule.setEnabled(enabled);
    RiskExclusionRule saved = exclusionRuleRepository.save(rule);
    lastSyncByOrg.clear();
    return toRuleView(saved);
  }

  @Transactional
  public void deleteRule(Long id) {
    RiskExclusionRule rule = exclusionRuleRepository.findById(id)
      .orElseThrow(() -> new IllegalArgumentException("剔除规则不存在"));
    if (Boolean.TRUE.equals(rule.getBuiltin())) {
      throw new IllegalArgumentException("预置规则不可删除，可停用");
    }
    exclusionRuleRepository.delete(rule);
    lastSyncByOrg.clear();
  }

  private RuleView toRuleView(RiskExclusionRule rule) {
    return new RuleView(rule.getId(), rule.getKeyword(), rule.getMatchType(), rule.getCategory(),
      rule.getEnabled(), rule.getBuiltin(), rule.getRemark());
  }

  // ==================== 内部工具 ====================

  /**
   * 指定组织子树内的流水：严格按组织层级取数，不叠加单条流水分享，避免跨组织扩散。
   */
  private List<BankStatement> statementsInOrgTree(Long orgId) {
    Set<Long> orgIds = orgScope.orgTree(orgId);
    if (orgIds.isEmpty()) {
      return List.of();
    }
    Specification<BankStatement> spec = (root, query, cb) ->
      cb.coalesce(root.get("orgId"), OrgScopeService.ROOT_ORG_ID).in(orgIds);
    return statementRepository.findAll(spec);
  }

  private List<BankStatement> statementsOfCurrentOrgScope(Long userId) {
    return statementsInOrgTree(orgScope.dataOrgId(userId));
  }

  private List<BankStatementTransaction> loadTransactions(List<BankStatement> statements) {
    if (statements.isEmpty()) {
      return List.of();
    }
    List<Long> ids = statements.stream().map(BankStatement::getId).toList();
    Specification<BankStatementTransaction> spec = (root, query, cb) ->
      root.get("statement").get("id").in(ids);
    return transactionRepository.findAll(spec);
  }

  private List<String> matchRules(String nameKey, List<RiskExclusionRule> rules) {
    List<String> matched = new ArrayList<>();
    for (RiskExclusionRule rule : rules) {
      String keyword = normalize(rule.getKeyword());
      if (!keyword.isEmpty() && matches(nameKey, keyword, rule.getMatchType())) {
        matched.add(rule.getKeyword());
      }
    }
    return matched;
  }

  private boolean matches(String nameKey, String normalizedKeyword, String matchType) {
    return RiskExclusionRule.MATCH_EXACT.equals(matchType)
      ? nameKey.equals(normalizedKeyword)
      : nameKey.contains(normalizedKeyword);
  }

  /**
   * 公司主体保护：名称含「有限公司/公司/集团/银行/事务所」时不判为消费型大众对手。
   */
  private static boolean isCompanyEntity(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      return false;
    }
    return displayName.contains("有限公司") || displayName.contains("公司")
      || displayName.contains("集团") || displayName.contains("银行")
      || displayName.contains("事务所");
  }

  /**
   * 当前组织被判定为消费型大众对手的名称键集合，供流水分析/网贷匹配复用，避免逐行查库。
   */
  public Set<String> consumerNameKeys(Long userId) {
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      return Set.of();
    }
    return counterpartyRepository.findByOrgIdAndConsumer(orgId, Boolean.TRUE).stream()
      .map(RiskCounterparty::getNameKey)
      .collect(java.util.stream.Collectors.toSet());
  }

  /**
   * 名称清洗：去掉所有空白（含全角空格），英文转小写。
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length(); i++) {
      char c = raw.charAt(i);
      if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
        continue;
      }
      sb.append(c);
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  private static String displayName(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replaceAll("[\\s\\u3000]+", " ").trim();
  }

  private static String digitsOnly(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isDigit(c)) {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String mostFrequent(Map<String, Integer> counts) {
    return counts.entrySet().stream()
      .max(Comparator.comparingInt(Map.Entry::getValue))
      .map(Map.Entry::getKey)
      .orElse("");
  }

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static class CpAgg {
    private final Map<String, Integer> displayCounts = new HashMap<>();
    private final Set<String> accounts = new LinkedHashSet<>();
    private final Set<String> holders = new LinkedHashSet<>();
    private int txnCount;
    private int incomeCount;
    private BigDecimal incomeTotal = BigDecimal.ZERO;
    private int expenseCount;
    private BigDecimal expenseTotal = BigDecimal.ZERO;
    private LocalDate firstDate;
    private LocalDate lastDate;
  }

  private static class CustAgg {
    private String name;
    private int statementCount;
    private int transactionCount;
    private final Set<String> accounts = new LinkedHashSet<>();
    private BigDecimal incomeTotal = BigDecimal.ZERO;
    private BigDecimal expenseTotal = BigDecimal.ZERO;
    private LocalDate firstDate;
    private LocalDate lastDate;
  }

  // ==================== DTO ====================

  public record TagView(Long id, String code, String name, String color, String subjectType,
                        String source, Boolean builtin, String description) {
  }

  public record CounterpartyView(Long id, String nameKey, String displayName, String accountNos,
                                 int accountCount, int txnCount, int incomeCount, BigDecimal incomeTotal,
                                 int expenseCount, BigDecimal expenseTotal, int holderCount,
                                 LocalDate firstDate, LocalDate lastDate, boolean consumer,
                                 String consumerRules, List<TagView> tags) {
  }

  public record CustomerView(String nameKey, String name, int accountCount, int statementCount,
                             int transactionCount, int creditReportCount, BigDecimal totalIncome,
                             BigDecimal totalExpense, LocalDate firstDate, LocalDate lastDate,
                             List<TagView> tags) {
  }

  public record RuleView(Long id, String keyword, String matchType, String category,
                         Boolean enabled, Boolean builtin, String remark) {
  }

  public record RuleStat(String keyword, String matchType, long count, long txnCount) {
  }

  public record SyncResult(int total, int consumerCount, int keptCount, long consumerTxnCount,
                           LocalDateTime syncTime, List<RuleStat> rules, List<String> consumerSamples) {
  }

  public record Summary(int counterpartyTotal, int consumerCount, int keptCount, long consumerTxnCount,
                        int customerCount, int blacklistCustomerCount, int funderCount, int ruleCount,
                        LocalDateTime syncTime, List<RuleStat> rules, List<String> consumerSamples) {
  }
}
