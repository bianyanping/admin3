package tech.wetech.admin3.lending.service;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.common.scope.OrgScopeService;
import tech.wetech.admin3.lending.model.LendingPlatform;
import tech.wetech.admin3.lending.model.LendingTransactionMatch;
import tech.wetech.admin3.lending.repository.LendingPlatformRepository;
import tech.wetech.admin3.lending.repository.LendingTransactionMatchRepository;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.repository.BankStatementRepository;
import tech.wetech.admin3.ocrApi.repository.BankStatementTransactionRepository;
import tech.wetech.admin3.ocrApi.repository.BankStatementTransactionRepository.LendingSourceView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 网贷识别与统计服务。
 *
 * <p>匹配口径：
 * <ul>
 *   <li>数据源：交易对手户名（counterpartyName）或摘要（summary）</li>
 *   <li>规则：命中任一启用平台的关键词即为网贷相关</li>
 *   <li>一笔流水命中多个关键词时，取最长（最具体）的关键词，只记一条，避免重复统计</li>
 *   <li>命中结果落在 lending_transaction_match，可整表重建，不改动流水表</li>
 * </ul>
 */
@Service
public class LendingService {

  public static final String FIELD_COUNTERPARTY_NAME = "COUNTERPARTY_NAME";
  public static final String FIELD_SUMMARY = "SUMMARY";

  /**
   * 客户筛选中代表未命名户主的特殊值。
   */
  public static final String UNKNOWN_HOLDER = "__UNKNOWN__";
  public static final String UNKNOWN_HOLDER_LABEL = "未命名户主";

  /**
   * 系统内置兜底平台：正式平台都未命中时，才用通用贷款类关键词兜底。
   */
  public static final String FALLBACK_PLATFORM_NAME = "其他平台";
  public static final String FALLBACK_KEYWORDS =
    "网贷,网络贷款,网络小贷,小额贷款,小额贷,消费金融,消费贷,信用贷款,信用贷,借款,借贷,还款,分期,贷款,借钱,资金周转";

  /**
   * 预置平台。一键初始化时仅补充库里不存在的名称，可重复执行。
   */
  private static final List<Preset> PRESETS = List.of(
    new Preset("借呗", "借呗,蚂蚁借呗", "支付宝消费信贷"),
    new Preset("花呗", "花呗", "支付宝消费信贷；可能误伤花呗收款，可按需停用"),
    new Preset("微信分付", "分付", "微信支付消费信贷"),
    new Preset("微粒贷", "微粒贷", "微众银行消费信贷"),
    new Preset("京东金条", "京东金条", "京东金融现金借贷"),
    new Preset("京东白条", "京东白条", "京东金融消费信贷"),
    new Preset("度小满", "度小满,有钱花", "原百度金融"),
    new Preset("360借条", "360借条,奇富借条", "奇富科技"),
    new Preset("分期乐", "分期乐", "乐信旗下消费信贷"),
    new Preset("美团借钱", "美团借钱,美团小贷", "美团消费信贷"),
    new Preset("美团月付", "美团月付", "美团消费信贷"),
    new Preset("抖音放心借", "放心借", "抖音/今日头条借贷"),
    new Preset("抖音月付", "抖音月付", "抖音消费信贷"),
    new Preset("招联金融", "招联金融,招联消费金融", "招联消费金融"),
    new Preset("马上消费金融", "马上消费金融,马上金融,安逸花", "持牌消费金融"),
    new Preset("中邮消费金融", "中邮消费金融,中邮钱包", "持牌消费金融"),
    new Preset("兴业消费金融", "兴业消费金融", "持牌消费金融"),
    new Preset("中原消费金融", "中原消费金融", "持牌消费金融"),
    new Preset("哈银消费金融", "哈银消费金融", "持牌消费金融"),
    new Preset("捷信消费金融", "捷信消费金融,捷信", "持牌消费金融"),
    new Preset("平安普惠", "平安普惠", "陆金所控股"),
    new Preset("陆金所", "陆金所", "平安系"),
    new Preset("拍拍贷", "拍拍贷", "信也科技"),
    new Preset("宜人贷", "宜人贷,宜信", "宜信旗下"),
    new Preset("小赢卡贷", "小赢卡贷", "小赢科技"),
    new Preset("还呗", "还呗", "数禾科技"),
    new Preset("省呗", "省呗", "萨摩耶数科"),
    new Preset("小米贷款", "小米贷款,天星金融", "小米消费金融"),
    new Preset("网商贷", "网商贷", "网商银行经营贷")
  );

  private final LendingPlatformRepository platformRepository;
  private final LendingTransactionMatchRepository matchRepository;
  private final BankStatementTransactionRepository transactionRepository;
  private final BankStatementRepository statementRepository;
  private final OrgScopeService orgScope;
  private final tech.wetech.admin3.risk.repository.RiskCounterpartyRepository riskCounterpartyRepository;
  /** 已按组织完成懒初始化的组织，避免每次请求重复检查 */
  private final Set<Long> initializedOrgs = java.util.concurrent.ConcurrentHashMap.newKeySet();

  public LendingService(LendingPlatformRepository platformRepository,
                        LendingTransactionMatchRepository matchRepository,
                        BankStatementTransactionRepository transactionRepository,
                        BankStatementRepository statementRepository,
                        OrgScopeService orgScope,
                        tech.wetech.admin3.risk.repository.RiskCounterpartyRepository riskCounterpartyRepository) {
    this.platformRepository = platformRepository;
    this.matchRepository = matchRepository;
    this.transactionRepository = transactionRepository;
    this.statementRepository = statementRepository;
    this.orgScope = orgScope;
    this.riskCounterpartyRepository = riskCounterpartyRepository;
  }

  // ==================== 关键词库维护 ====================

  public Page<LendingPlatform> listPlatforms(Long userId, String keyword, Boolean enabled, int page, int size) {
    Long orgId = orgScope.dataOrgId(userId);
    Specification<LendingPlatform> spec = (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(orgId == null ? cb.disjunction() : cb.equal(root.get("orgId"), orgId));
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim() + "%";
        predicates.add(cb.or(cb.like(root.get("name"), like), cb.like(root.get("keywords"), like)));
      }
      if (enabled != null) {
        predicates.add(cb.equal(root.get("enabled"), enabled));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.ASC, "id"));
    return platformRepository.findAll(spec, pageable);
  }

  public List<PlatformOption> listPlatformOptions(Long userId) {
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      return List.of();
    }
    Specification<LendingPlatform> spec = (root, query, cb) -> cb.equal(root.get("orgId"), orgId);
    return platformRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "id")).stream()
      .map(p -> new PlatformOption(p.getId(), p.getName(), p.getEnabled()))
      .toList();
  }

  @Transactional
  public LendingPlatform createPlatform(Long userId, String name, String keywords, Boolean enabled, String remark) {
    Long orgId = requireDataOrgId(userId);
    String platformName = requireName(name);
    if (platformRepository.existsByNameAndOrgId(platformName, orgId)) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "平台名称已存在：" + platformName);
    }
    LendingPlatform platform = new LendingPlatform();
    platform.setOrgId(orgId);
    platform.setName(platformName);
    platform.setKeywords(joinKeywords(keywords));
    platform.setEnabled(enabled == null || enabled);
    platform.setBuiltin(Boolean.FALSE);
    platform.setRemark(remark);
    return platformRepository.save(platform);
  }

  @Transactional
  public LendingPlatform updatePlatform(Long userId, Long id, String name, String keywords, Boolean enabled,
                                        String remark) {
    LendingPlatform platform = findPlatform(userId, id);
    Long orgId = platform.getOrgId();
    // 内置兜底平台名称固定，关键词可改，避免初始化时按名称重复插入
    String platformName = Boolean.TRUE.equals(platform.getBuiltin())
      ? FALLBACK_PLATFORM_NAME : requireName(name);
    platformRepository.findByNameAndOrgId(platformName, orgId)
      .filter(other -> !other.getId().equals(id))
      .ifPresent(other -> {
        throw new BusinessException(CommonResultStatus.PARAM_ERROR, "平台名称已存在：" + platformName);
      });
    platform.setName(platformName);
    platform.setKeywords(joinKeywords(keywords));
    if (enabled != null) {
      platform.setEnabled(enabled);
    }
    platform.setRemark(remark);
    return platformRepository.save(platform);
  }

  @Transactional
  public void deletePlatform(Long userId, Long id) {
    LendingPlatform platform = findPlatform(userId, id);
    if (Boolean.TRUE.equals(platform.getBuiltin())) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR,
        "「其他平台」是系统内置兜底项，不能删除；可停用或修改关键词");
    }
    platformRepository.delete(platform);
  }

  @Transactional
  public LendingPlatform setPlatformEnabled(Long userId, Long id, boolean enabled) {
    LendingPlatform platform = findPlatform(userId, id);
    platform.setEnabled(enabled);
    return platformRepository.save(platform);
  }

  private LendingPlatform findPlatform(Long userId, Long id) {
    LendingPlatform platform = platformRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "网贷平台不存在"));
    if (!orgScope.isAdmin(userId)) {
      Long orgId = orgScope.dataOrgId(userId);
      if (orgId == null || !orgId.equals(platform.getOrgId())) {
        throw new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "网贷平台不存在");
      }
    }
    return platform;
  }

  /**
   * 一键初始化预置平台：仅作用于当前组织，按名称判重，已存在则跳过；内置兜底平台只补一次。
   */
  @Transactional
  public InitResult initPresets(Long userId) {
    Long orgId = requireDataOrgId(userId);
    int inserted = 0;
    int skipped = 0;
    if (ensureBuiltinPlatform(orgId)) {
      inserted++;
    } else {
      skipped++;
    }
    for (Preset preset : PRESETS) {
      if (platformRepository.existsByNameAndOrgId(preset.name(), orgId)) {
        skipped++;
        continue;
      }
      LendingPlatform platform = new LendingPlatform();
      platform.setOrgId(orgId);
      platform.setName(preset.name());
      platform.setKeywords(preset.keywords());
      platform.setEnabled(Boolean.TRUE);
      platform.setBuiltin(Boolean.FALSE);
      platform.setRemark(preset.remark());
      platformRepository.save(platform);
      inserted++;
    }
    return new InitResult(inserted, skipped, platformRepository.countByOrgId(orgId));
  }

  /**
   * 保证指定组织的系统内置「其他平台」存在；已存在时只纠正内置标记，不覆盖用户改过的关键词。
   *
   * @return 是否新建
   */
  @Transactional
  public boolean ensureBuiltinPlatform(Long orgId) {
    return platformRepository.findByNameAndOrgId(FALLBACK_PLATFORM_NAME, orgId)
      .map(existing -> {
        if (!Boolean.TRUE.equals(existing.getBuiltin())) {
          existing.setBuiltin(Boolean.TRUE);
          platformRepository.save(existing);
        }
        return false;
      })
      .orElseGet(() -> {
        LendingPlatform platform = new LendingPlatform();
        platform.setOrgId(orgId);
        platform.setName(FALLBACK_PLATFORM_NAME);
        platform.setKeywords(FALLBACK_KEYWORDS);
        platform.setEnabled(Boolean.TRUE);
        platform.setBuiltin(Boolean.TRUE);
        platform.setRemark("系统内置兜底项：正式平台都未命中时才参与匹配，可修改关键词或停用");
        platformRepository.save(platform);
        return true;
      });
  }

  // ==================== 匹配刷新 ====================

  /**
   * 重建当前组织的命中记录：清空本组织数据后按本组织关键词库重新匹配本组织子树流水。
   * 只读流水表，不修改任何流水数据。
   */
  @Transactional
  public RefreshResult refreshMatches(Long userId) {
    Long orgId = requireDataOrgId(userId);
    long started = System.currentTimeMillis();
    List<LendingPlatform> platforms = platformRepository.findByEnabledTrueAndOrgIdOrderByIdAsc(orgId);
    List<Rule> formalRules = buildRules(platforms, false);
    List<Rule> fallbackRules = buildRules(platforms, true);

    matchRepository.deleteByOrgId(orgId);
    List<LendingSourceView> sources = transactionRepository.findLendingSourcesByOrgIds(
      orgScope.orgTree(orgId), OrgScopeService.ROOT_ORG_ID);
    // 消费型大众对手不参与网贷命中（按当前组织沉淀的对手库）
    Set<String> consumerKeys = riskCounterpartyRepository.findByOrgIdAndConsumer(orgId, Boolean.TRUE).stream()
      .map(tech.wetech.admin3.risk.model.RiskCounterparty::getNameKey)
      .collect(java.util.stream.Collectors.toSet());
    List<LendingTransactionMatch> matches = new ArrayList<>();
    long income = 0;
    long expense = 0;
    long other = 0;
    for (LendingSourceView source : sources) {
      if (consumerKeys.contains(normalize(source.getCounterpartyName()))) {
        continue;
      }
      String name = normalize(source.getCounterpartyName());
      String summary = normalize(source.getSummary());
      // 正式平台优先；只有正式平台全未命中时，才启用内置「其他平台」兜底
      Hit hit = findHit(formalRules, name, summary);
      if (hit == null) {
        hit = findHit(fallbackRules, name, summary);
      }
      if (hit == null) {
        continue;
      }
      String direction = resolveDirection(source.getDirection(), source.getAmount());
      LendingTransactionMatch match = new LendingTransactionMatch();
      match.setOrgId(orgId);
      match.setTransactionId(source.getId());
      match.setStatementId(source.getStatementId());
      match.setAccountHolder(normalizeHolder(source.getAccountHolder()));
      match.setBankName(source.getBankName());
      match.setAccountNo(source.getAccountNo());
      match.setTransDate(source.getTransDate());
      match.setTransMonth(source.getTransDate() == null ? null : YearMonth.from(source.getTransDate()).toString());
      match.setAmount(source.getAmount());
      match.setDirection(direction);
      match.setCounterpartyName(source.getCounterpartyName());
      match.setSummary(source.getSummary());
      match.setPlatformId(hit.rule().platformId());
      match.setPlatformName(hit.rule().platformName());
      match.setKeyword(hit.rule().keyword());
      match.setMatchedField(hit.matchedField());
      matches.add(match);
      if ("入".equals(direction)) {
        income++;
      } else if ("出".equals(direction)) {
        expense++;
      } else {
        other++;
      }
    }
    // 分批写入，避免单次大批量
    for (int i = 0; i < matches.size(); i += 500) {
      matchRepository.saveAll(matches.subList(i, Math.min(i + 500, matches.size())));
    }

    initializedOrgs.add(orgId);
    long elapsed = System.currentTimeMillis() - started;
    long matchedPlatforms = matches.stream().map(LendingTransactionMatch::getPlatformId).distinct().count();
    return new RefreshResult(sources.size(), matches.size(), income, expense, other,
      matchedPlatforms, elapsed, hitRate(matches.size(), sources.size()));
  }

  /**
   * 组织首次访问时懒初始化：补齐内置兜底平台，并在命中记录缺失/过期时按当前组织重建。
   */
  private void ensureOrgReady(Long userId) {
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      return;
    }
    ensureBuiltinPlatform(orgId);
    if (initializedOrgs.contains(orgId)) {
      return;
    }
    long stale = matchRepository.countStaleMatchesByOrgId(orgId);
    boolean empty = matchRepository.countByOrgId(orgId) == 0;
    if (stale > 0 || (empty && hasLendingSources(orgId))) {
      refreshMatches(userId);
    }
    initializedOrgs.add(orgId);
  }

  private boolean hasLendingSources(Long orgId) {
    return transactionRepository.countLendingSourcesByOrgIds(orgScope.orgTree(orgId),
      OrgScopeService.ROOT_ORG_ID) > 0;
  }

  // ==================== 月度统计 ====================

  @Transactional
  public MonthlyStats monthlyStats(Long userId, String startMonth, String endMonth, String accountHolder) {
    YearMonth start = parseMonth(startMonth, "开始月份");
    YearMonth end = parseMonth(endMonth, "结束月份");
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      return new MonthlyStats(List.of(), emptyDirection(), emptyDirection(), 0, 0, 0, 0, List.of());
    }
    ensureOrgReady(userId);
    // 命中率分母：当前组织子树内的流水笔数，不再使用全库
    long totalTransactions = transactionRepository.countLendingSourcesByOrgIds(orgScope.orgTree(orgId),
      OrgScopeService.ROOT_ORG_ID);
    Specification<LendingTransactionMatch> spec = (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(root.get("orgId"), orgId));
      if (start != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("transMonth"), start.toString()));
      }
      if (end != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("transMonth"), end.toString()));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
    List<LendingTransactionMatch> matches = matchRepository.findAll(spec);

    Map<String, MonthAgg> monthMap = new LinkedHashMap<>();
    Map<String, CustomerAgg> customerMap = new LinkedHashMap<>();
    long otherCount = 0;
    long filteredCount = 0;
    for (LendingTransactionMatch match : matches) {
      // 客户汇总始终按日期范围内全量计算，便于切换客户
      customerMap.computeIfAbsent(holderKey(match.getAccountHolder()), CustomerAgg::new).add(match);
      if (!holderMatches(match.getAccountHolder(), accountHolder)) {
        continue;
      }
      filteredCount++;
      if (match.getTransMonth() == null) {
        if (!"入".equals(match.getDirection()) && !"出".equals(match.getDirection())) {
          otherCount++;
        }
        continue;
      }
      MonthAgg month = monthMap.computeIfAbsent(match.getTransMonth(), k -> new MonthAgg());
      if ("入".equals(match.getDirection())) {
        month.received.add(match);
      } else if ("出".equals(match.getDirection())) {
        month.deducted.add(match);
      } else {
        month.otherCount++;
        otherCount++;
      }
    }

    List<MonthlyRow> rows = new ArrayList<>();
    DirectionAgg totalReceived = new DirectionAgg();
    DirectionAgg totalDeducted = new DirectionAgg();
    monthMap.keySet().stream().sorted().forEach(month -> {
      MonthAgg agg = monthMap.get(month);
      totalReceived.merge(agg.received);
      totalDeducted.merge(agg.deducted);
      rows.add(new MonthlyRow(month, agg.received.toStats(), agg.deducted.toStats(), agg.otherCount));
    });
    List<CustomerSummary> customers = customerMap.values().stream()
      .sorted(Comparator.comparing((CustomerAgg c) -> c.totalAmount()).reversed())
      .map(CustomerAgg::toSummary)
      .toList();
    return new MonthlyStats(rows, totalReceived.toStats(), totalDeducted.toStats(), otherCount,
      filteredCount, totalTransactions, hitRate(filteredCount, totalTransactions), customers);
  }

  // ==================== 明细 ====================

  @Transactional
  public Page<LendingTransactionMatch> transactions(Long userId, String month, Long platformId,
                                                    String direction, String accountHolder,
                                                    int page, int size) {
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      return Page.empty();
    }
    ensureOrgReady(userId);
    Specification<LendingTransactionMatch> spec = (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(root.get("orgId"), orgId));
      if (month != null && !month.isBlank()) {
        predicates.add(cb.equal(root.get("transMonth"), month.trim()));
      }
      if (platformId != null) {
        predicates.add(cb.equal(root.get("platformId"), platformId));
      }
      if (direction != null && !direction.isBlank()) {
        predicates.add(cb.equal(root.get("direction"), direction.trim()));
      }
      if (accountHolder != null && !accountHolder.isBlank()) {
        String holder = accountHolder.trim();
        if (UNKNOWN_HOLDER.equals(holder)) {
          predicates.add(cb.or(cb.isNull(root.get("accountHolder")), cb.equal(root.get("accountHolder"), "")));
        } else {
          predicates.add(cb.equal(root.get("accountHolder"), holder));
        }
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size,
      Sort.by(Sort.Direction.DESC, "transDate").and(Sort.by(Sort.Direction.DESC, "id")));
    return matchRepository.findAll(spec, pageable);
  }

  /**
   * 客户（账户户名）选项，来源于当前组织子树内的流水单。
   */
  public List<CustomerOption> listCustomers(Long userId) {
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      return List.of();
    }
    Set<Long> orgIds = orgScope.orgTree(orgId);
    if (orgIds.isEmpty()) {
      return List.of();
    }
    Specification<BankStatement> spec = (root, query, cb) ->
      cb.coalesce(root.get("orgId"), OrgScopeService.ROOT_ORG_ID).in(orgIds);
    Map<String, Set<String>> accountMap = new LinkedHashMap<>();
    for (BankStatement statement : statementRepository.findAll(spec)) {
      String key = holderKey(statement.getAccountHolder());
      Set<String> accounts = accountMap.computeIfAbsent(key, k -> new LinkedHashSet<>());
      if (statement.getAccountNo() != null && !statement.getAccountNo().isBlank()) {
        accounts.add(statement.getAccountNo());
      }
    }
    return accountMap.entrySet().stream()
      .sorted(Map.Entry.comparingByKey())
      .map(entry -> new CustomerOption(entry.getKey(), holderLabel(entry.getKey()), entry.getValue().size()))
      .toList();
  }

  // ==================== 内部工具 ====================

  private Long requireDataOrgId(Long userId) {
    Long orgId = orgScope.dataOrgId(userId);
    if (orgId == null) {
      throw new BusinessException(CommonResultStatus.FORBIDDEN, "当前用户未分配组织，无法写入业务数据");
    }
    return orgId;
  }

  private static String requireName(String name) {
    String value = name == null ? "" : name.trim();
    if (value.isEmpty()) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "平台名称不能为空");
    }
    return value;
  }

  private static String normalizeHolder(String holder) {
    return holder == null || holder.isBlank() ? null : holder.trim();
  }

  private static String holderKey(String holder) {
    return holder == null || holder.isBlank() ? UNKNOWN_HOLDER : holder.trim();
  }

  private static String holderLabel(String key) {
    return UNKNOWN_HOLDER.equals(key) ? UNKNOWN_HOLDER_LABEL : key;
  }

  private static boolean holderMatches(String holder, String filter) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    String value = filter.trim();
    if (UNKNOWN_HOLDER.equals(value)) {
      return holder == null || holder.isBlank();
    }
    return holder != null && holder.trim().equals(value);
  }

  private static String joinKeywords(String keywords) {
    List<String> parsed = parseKeywords(keywords);
    if (parsed.isEmpty()) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "请至少填写一个关键词");
    }
    return String.join(",", parsed);
  }

  public static List<String> parseKeywords(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split("[,，;；、\\n\\r]+"))
      .map(String::trim)
      .filter(value -> !value.isEmpty())
      .distinct()
      .toList();
  }

  private static String normalize(String value) {
    if (value == null) {
      return "";
    }
    // 去掉空白（含全角空格）并转小写，兼容英文关键词大小写与户名中的空格
    return value.replaceAll("[\\s\\u3000]+", "").toLowerCase(Locale.ROOT);
  }

  private static String resolveDirection(String direction, BigDecimal amount) {
    if ("入".equals(direction) || "出".equals(direction) || "其他".equals(direction)) {
      return direction;
    }
    if (amount == null) {
      return "其他";
    }
    return amount.signum() >= 0 ? "入" : "出";
  }

  /**
   * 按是否内置分组构建关键词规则，最长关键词优先，长度相同按平台 id 保证结果稳定。
   */
  private static List<Rule> buildRules(List<LendingPlatform> platforms, boolean builtin) {
    List<Rule> rules = new ArrayList<>();
    for (LendingPlatform platform : platforms) {
      if (Boolean.TRUE.equals(platform.getBuiltin()) != builtin) {
        continue;
      }
      for (String keyword : parseKeywords(platform.getKeywords())) {
        rules.add(new Rule(platform.getId(), platform.getName(), keyword, normalize(keyword)));
      }
    }
    rules.sort(Comparator.comparingInt((Rule r) -> r.normalizedKeyword().length()).reversed()
      .thenComparing(Rule::platformId));
    return rules;
  }

  private static Hit findHit(List<Rule> rules, String name, String summary) {
    for (Rule rule : rules) {
      if (rule.normalizedKeyword().isEmpty()) {
        continue;
      }
      if (name.contains(rule.normalizedKeyword())) {
        return new Hit(rule, FIELD_COUNTERPARTY_NAME);
      }
      if (summary.contains(rule.normalizedKeyword())) {
        return new Hit(rule, FIELD_SUMMARY);
      }
    }
    return null;
  }

  private static YearMonth parseMonth(String month, String label) {
    if (month == null || month.isBlank()) {
      return null;
    }
    try {
      return YearMonth.parse(month.trim());
    } catch (DateTimeParseException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, label + "格式应为 yyyy-MM");
    }
  }

  private static double hitRate(long matched, long total) {
    if (total <= 0) {
      return 0;
    }
    return Math.round(matched * 10000.0 / total) / 100.0;
  }

  private static DirectionStats emptyDirection() {
    return new DirectionStats(BigDecimal.ZERO, 0, 0, List.of());
  }

  // ==================== 内部类型 ====================

  private record Preset(String name, String keywords, String remark) {
  }

  private record Rule(Long platformId, String platformName, String keyword, String normalizedKeyword) {
  }

  private record Hit(Rule rule, String matchedField) {
  }

  private static class PlatformAgg {
    private final Long platformId;
    private final String platformName;
    private BigDecimal amount = BigDecimal.ZERO;
    private long count;

    PlatformAgg(LendingTransactionMatch match) {
      this.platformId = match.getPlatformId();
      this.platformName = match.getPlatformName();
    }

    PlatformAgg(Long platformId, String platformName) {
      this.platformId = platformId;
      this.platformName = platformName;
    }

    void add(LendingTransactionMatch match) {
      if (match.getAmount() != null) {
        amount = amount.add(match.getAmount().abs());
      }
      count++;
    }

    PlatformAmount toAmount() {
      return new PlatformAmount(platformId, platformName, amount, count);
    }
  }

  private static class DirectionAgg {
    private BigDecimal amount = BigDecimal.ZERO;
    private long count;
    private final Map<Long, PlatformAgg> platforms = new HashMap<>();

    void add(LendingTransactionMatch match) {
      if (match.getAmount() != null) {
        amount = amount.add(match.getAmount().abs());
      }
      count++;
      platforms.computeIfAbsent(match.getPlatformId(), k -> new PlatformAgg(match)).add(match);
    }

    void merge(DirectionAgg other) {
      amount = amount.add(other.amount);
      count += other.count;
      for (PlatformAgg platform : other.platforms.values()) {
        PlatformAgg target = platforms.computeIfAbsent(platform.platformId,
          k -> new PlatformAgg(platform.platformId, platform.platformName));
        target.amount = target.amount.add(platform.amount);
        target.count += platform.count;
      }
    }

    DirectionStats toStats() {
      List<PlatformAmount> list = platforms.values().stream()
        .map(PlatformAgg::toAmount)
        .sorted(Comparator.comparing(PlatformAmount::amount).reversed())
        .toList();
      return new DirectionStats(amount, count, platforms.size(), list);
    }
  }

  private static class MonthAgg {
    private final DirectionAgg received = new DirectionAgg();
    private final DirectionAgg deducted = new DirectionAgg();
    private long otherCount;
  }

  /**
   * 按客户（账户户名）聚合的命中数据。
   */
  private static class CustomerAgg {
    private final String holderKey;
    private final Set<String> accounts = new LinkedHashSet<>();
    private final DirectionAgg received = new DirectionAgg();
    private final DirectionAgg deducted = new DirectionAgg();
    private long matchCount;

    CustomerAgg(String holderKey) {
      this.holderKey = holderKey;
    }

    void add(LendingTransactionMatch match) {
      matchCount++;
      if (match.getAccountNo() != null && !match.getAccountNo().isBlank()) {
        accounts.add(match.getAccountNo());
      }
      if ("入".equals(match.getDirection())) {
        received.add(match);
      } else if ("出".equals(match.getDirection())) {
        deducted.add(match);
      }
    }

    BigDecimal totalAmount() {
      return received.amount.add(deducted.amount);
    }

    CustomerSummary toSummary() {
      return new CustomerSummary(holderKey, holderLabel(holderKey), accounts.size(), matchCount,
        received.toStats(), deducted.toStats());
    }
  }

  // ==================== 对外 DTO ====================

  public record PlatformOption(Long id, String name, Boolean enabled) {
  }

  public record InitResult(int inserted, int skipped, long total) {
  }

  public record RefreshResult(long scannedCount, long matchedCount, long incomeCount, long expenseCount,
                              long otherCount, long platformCount, long elapsedMs, double hitRate) {
  }

  public record PlatformAmount(Long platformId, String platformName, BigDecimal amount, long count) {
  }

  public record DirectionStats(BigDecimal amount, long count, int platformCount,
                               List<PlatformAmount> platforms) {
  }

  public record MonthlyRow(String month, DirectionStats received, DirectionStats deducted, long otherCount) {
  }

  public record CustomerOption(String value, String label, int accountCount) {
  }

  public record CustomerSummary(String value, String accountHolder, int accountCount, long matchCount,
                                DirectionStats received, DirectionStats deducted) {
  }

  public record MonthlyStats(List<MonthlyRow> months, DirectionStats totalReceived, DirectionStats totalDeducted,
                             long otherCount, long matchedCount, long totalTransactionCount, double hitRate,
                             List<CustomerSummary> customers) {
  }
}
