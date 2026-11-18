package tech.wetech.admin3.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.common.authz.RequiresPermissions;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.model.BankStatementShare;
import tech.wetech.admin3.ocrApi.model.BankStatementTransaction;
import tech.wetech.admin3.ocrApi.repository.BankStatementRepository;
import tech.wetech.admin3.ocrApi.repository.BankStatementShareRepository;
import tech.wetech.admin3.ocrApi.repository.BankStatementTransactionRepository;
import tech.wetech.admin3.ocrApi.service.BankStatementScopeService;
import tech.wetech.admin3.risk.model.RiskTag;
import tech.wetech.admin3.risk.service.RiskAlertService;
import tech.wetech.admin3.risk.service.RiskDatabaseService;
import tech.wetech.admin3.sys.model.StorageFile;
import tech.wetech.admin3.sys.model.User;
import tech.wetech.admin3.sys.repository.UserRepository;
import tech.wetech.admin3.sys.service.StorageService;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 银行流水查询与下载接口。
 *
 * <p>数据隔离：用户只能看到自己组织子树范围内的流水单，或被分享给自己的流水单。
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/bank-statements")
public class BankStatementController {

  private final BankStatementRepository bankStatementRepository;
  private final BankStatementTransactionRepository transactionRepository;
  private final StorageService storageService;
  private final BankStatementScopeService scopeService;
  private final BankStatementShareRepository shareRepository;
  private final UserRepository userRepository;
  private final RiskDatabaseService riskDatabaseService;
  private final RiskAlertService riskAlertService;

  public BankStatementController(BankStatementRepository bankStatementRepository,
                                 BankStatementTransactionRepository transactionRepository,
                                 StorageService storageService,
                                 BankStatementScopeService scopeService,
                                 BankStatementShareRepository shareRepository,
                                 UserRepository userRepository,
                                 RiskDatabaseService riskDatabaseService,
                                 RiskAlertService riskAlertService) {
    this.bankStatementRepository = bankStatementRepository;
    this.transactionRepository = transactionRepository;
    this.storageService = storageService;
    this.scopeService = scopeService;
    this.shareRepository = shareRepository;
    this.userRepository = userRepository;
    this.riskDatabaseService = riskDatabaseService;
    this.riskAlertService = riskAlertService;
  }

  private Long currentUserId() {
    UserinfoDTO currentUser = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    return currentUser == null ? null : currentUser.userId();
  }

  /**
   * 流水单分页列表。
   */
  @GetMapping
  @RequiresPermissions("bankStatement:view")
  public ResponseEntity<Page<BankStatement>> list(
    @RequestParam(value = "bankCode", required = false) String bankCode,
    @RequestParam(value = "accountHolder", required = false) String accountHolder,
    @RequestParam(value = "accountNo", required = false) String accountNo,
    @RequestParam(value = "keyword", required = false) String keyword,
    @RequestParam(value = "startDateFrom", required = false) LocalDate startDateFrom,
    @RequestParam(value = "startDateTo", required = false) LocalDate startDateTo,
    @RequestParam(value = "page", defaultValue = "1") int page,
    @RequestParam(value = "size", defaultValue = "20") int size) {

    Specification<BankStatement> spec = (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (bankCode != null && !bankCode.isBlank()) {
        predicates.add(cb.equal(root.get("bankCode"), bankCode));
      }
      if (accountHolder != null && !accountHolder.isBlank()) {
        predicates.add(cb.like(root.get("accountHolder"), "%" + accountHolder + "%"));
      }
      if (accountNo != null && !accountNo.isBlank()) {
        predicates.add(cb.like(root.get("accountNo"), "%" + accountNo + "%"));
      }
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword.trim() + "%";
        predicates.add(cb.or(
          cb.like(root.get("accountHolder"), like),
          cb.like(root.get("accountNo"), like)
        ));
      }
      if (startDateFrom != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("startDate"), startDateFrom));
      }
      if (startDateTo != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("startDate"), startDateTo));
      }
      // ========== 数据隔离：orgId 在本人组织子树内 OR 被分享给本人 ==========
      Long userId = currentUserId();
      if (userId != null) {
        BankStatementScopeService.Visibility visibility = scopeService.visibilityOf(userId);
        if (visibility.nothing()) {
          predicates.add(cb.disjunction()); // 无任何可见范围
        } else {
          List<Predicate> visible = new ArrayList<>();
          if (!visibility.orgIds().isEmpty()) {
            // orgId 为 NULL 的历史数据视为根节点数据（ScopeService.ROOT_ORG_ID）
            visible.add(cb.coalesce(root.get("orgId"), BankStatementScopeService.ROOT_ORG_ID)
              .in(visibility.orgIds()));
          }
          if (!visibility.sharedIds().isEmpty()) {
            visible.add(root.get("id").in(visibility.sharedIds()));
          }
          predicates.add(cb.or(visible.toArray(new Predicate[0])));
        }
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.DESC, "id"));
    return ResponseEntity.ok(bankStatementRepository.findAll(spec, pageable));
  }

  /**
   * 手动新增流水单。用于 OCR 未能识别户名/账号时，先建单再补录明细。
   */
  @PostMapping
  @RequiresPermissions("bankStatement:upload")
  public ResponseEntity<BankStatement> create(@RequestBody @Valid StatementRequest request) {
    BankStatement statement = new BankStatement();
    applyRequest(statement, request);
    statement.setTransactionCount(0);
    statement.setIncomeCount(0);
    statement.setExpenseCount(0);
    statement.setTotalIncome(BigDecimal.ZERO);
    statement.setTotalExpense(BigDecimal.ZERO);
    Long userId = currentUserId();
    if (userId != null) {
      userRepository.findOrgIdByUserId(userId).ifPresent(orgId -> {
        statement.setOrgId(orgId);
        userRepository.findOrgNameByUserId(userId).ifPresent(statement::setOrgName);
      });
    }
    return new ResponseEntity<>(bankStatementRepository.save(statement), HttpStatus.CREATED);
  }

  /**
   * 手动修改流水单基本信息（户名、账号、账期等）。
   */
  @PutMapping("/{id}")
  @RequiresPermissions("bankStatement:upload")
  public ResponseEntity<BankStatement> update(@PathVariable Long id, @RequestBody @Valid StatementRequest request) {
    BankStatement statement = bankStatementRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
    scopeService.requireVisible(currentUserId(), statement);
    applyRequest(statement, request);
    return ResponseEntity.ok(bankStatementRepository.save(statement));
  }

  /**
   * 删除流水单（其明细一并删除）。
   */
  @DeleteMapping("/{id}")
  @RequiresPermissions("bankStatement:upload")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    BankStatement statement = bankStatementRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
    scopeService.requireVisible(currentUserId(), statement);
    shareRepository.deleteByStatementId(id);
    bankStatementRepository.delete(statement);
    return ResponseEntity.noContent().build();
  }

  private void applyRequest(BankStatement statement, StatementRequest request) {
    statement.setBankCode(request.bankCode());
    statement.setBankName(request.bankName());
    statement.setAccountHolder(request.accountHolder().trim());
    statement.setAccountNo(request.accountNo().trim());
    statement.setStartDate(request.startDate());
    statement.setEndDate(request.endDate());
    statement.setCurrency(request.currency());
  }

  record StatementRequest(@NotBlank String bankCode, String bankName,
                          @NotBlank String accountHolder, @NotBlank String accountNo,
                          @NotNull LocalDate startDate, @NotNull LocalDate endDate,
                          String currency) {
  }

  /**
   * 首页看板统计：核心指标、每日收支趋势、流水构成、对手方 TOP10、大额流水。
   */
  @GetMapping("/dashboard")
  @RequiresPermissions("bankStatement:view")
  public ResponseEntity<DashboardStats> dashboard(
    @RequestParam(value = "days", defaultValue = "30") int days,
    @RequestParam(value = "accountHolder", required = false) String accountHolder) {

    int window = Math.min(Math.max(days, 1), 365);
    LocalDate to = LocalDate.now();
    LocalDate from = to.minusDays(window - 1L);

    List<Long> statementIds = visibleStatementIds(currentUserId());
    List<BankStatementTransaction> txns = new ArrayList<>();
    if (!statementIds.isEmpty()) {
      Specification<BankStatementTransaction> spec = (root, query, cb) -> {
        List<Predicate> predicates = new ArrayList<>();
        predicates.add(root.get("statement").get("id").in(statementIds));
        predicates.add(cb.greaterThanOrEqualTo(root.get("transDate"), from));
        predicates.add(cb.lessThanOrEqualTo(root.get("transDate"), to));
        if (accountHolder != null && !accountHolder.isBlank()) {
          predicates.add(cb.equal(root.get("statement").get("accountHolder"), accountHolder));
        }
        return cb.and(predicates.toArray(new Predicate[0]));
      };
      txns = transactionRepository.findAll(spec);
    }

    BigDecimal totalIncome = BigDecimal.ZERO;
    BigDecimal totalExpense = BigDecimal.ZERO;
    long todayCount = 0;
    long anomalyCount = 0;
    Map<LocalDate, BigDecimal[]> daily = new TreeMap<>();
    Map<String, BigDecimal> byCategory = new HashMap<>();
    Map<String, BigDecimal> byCounterparty = new HashMap<>();
    List<BankStatementTransaction> large = new ArrayList<>();

    for (BankStatementTransaction t : txns) {
      BigDecimal amount = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
      // 微信「其他」类（零钱充值/提现等）不计收支
      boolean internal = "其他".equals(t.getDirection());
      if (!internal) {
        if (amount.signum() >= 0) {
          totalIncome = totalIncome.add(amount);
        } else {
          totalExpense = totalExpense.add(amount.abs());
        }
      }
      if (to.equals(t.getTransDate())) {
        todayCount++;
      }
      if (amount.abs().compareTo(ANOMALY_THRESHOLD) >= 0) {
        anomalyCount++;
        large.add(t);
      }
      if (t.getTransDate() != null && !internal) {
        BigDecimal[] bucket = daily.computeIfAbsent(t.getTransDate(),
          k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        if (amount.signum() >= 0) {
          bucket[0] = bucket[0].add(amount);
        } else {
          bucket[1] = bucket[1].add(amount.abs());
        }
      }
      byCategory.merge(classifySummary(t.getSummary()), amount.abs(), BigDecimal::add);
      if (t.getCounterpartyName() != null && !t.getCounterpartyName().isBlank()) {
        byCounterparty.merge(t.getCounterpartyName().trim(), amount.abs(), BigDecimal::add);
      }
    }

    // 每日趋势补齐区间内没有交易的日期，折线不断档
    List<DailyPoint> dailyPoints = new ArrayList<>();
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      BigDecimal[] v = daily.get(d);
      dailyPoints.add(new DailyPoint(d.toString(),
        v == null ? BigDecimal.ZERO : v[0],
        v == null ? BigDecimal.ZERO : v[1]));
    }

    // 构成占比
    BigDecimal sumAll = byCategory.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    List<CategoryStat> categories = byCategory.entrySet().stream()
      .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
      .limit(6)
      .map(e -> new CategoryStat(e.getKey(), e.getValue(),
        sumAll.signum() == 0 ? 0d : e.getValue().doubleValue() * 100d / sumAll.doubleValue()))
      .toList();

    // 对手方 TOP10
    List<CounterpartyStat> top = byCounterparty.entrySet().stream()
      .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
      .limit(10)
      .map(e -> new CounterpartyStat(e.getKey(), e.getValue()))
      .toList();

    // 最近大额流水（按日期倒序取 10 条）
    List<LargeItem> recentLarge = large.stream()
      .sorted(Comparator.comparing(BankStatementTransaction::getTransDate,
        Comparator.nullsLast(Comparator.reverseOrder())))
      .limit(10)
      .map(t -> new LargeItem(t.getId(),
        t.getStatement() == null ? null : t.getStatement().getId(),
        t.getTransDate(), t.getAmount(), t.getCounterpartyName(), t.getSummary()))
      .toList();

    return ResponseEntity.ok(new DashboardStats(
      txns.size(), totalIncome, totalExpense, todayCount, anomalyCount, ANOMALY_THRESHOLD,
      dailyPoints, categories, top, recentLarge));
  }

  /**
   * 可选户名列表：按户名聚合可见流水单数量与账户数。
   */
  @GetMapping("/account-holders")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<List<AccountHolderOption>> accountHolders() {
    List<Long> statementIds = visibleStatementIds(currentUserId());
    if (statementIds.isEmpty()) {
      return ResponseEntity.ok(List.of());
    }
    Specification<BankStatement> spec = (root, query, cb) -> root.get("id").in(statementIds);
    Map<String, Set<String>> accounts = new HashMap<>();
    Map<String, Integer> statementCounts = new HashMap<>();
    for (BankStatement statement : bankStatementRepository.findAll(spec)) {
      String holder = statement.getAccountHolder() == null ? "" : statement.getAccountHolder().trim();
      if (holder.isEmpty()) {
        continue;
      }
      statementCounts.merge(holder, 1, Integer::sum);
      accounts.computeIfAbsent(holder, k -> new HashSet<>()).add(statement.getAccountNo());
    }
    List<AccountHolderOption> result = statementCounts.entrySet().stream()
      .sorted(Map.Entry.comparingByKey())
      .map(e -> new AccountHolderOption(e.getKey(), e.getValue(), accounts.get(e.getKey()).size()))
      .toList();
    return ResponseEntity.ok(result);
  }

  /**
   * 按户名汇总：所有账户的总入账/总出款，以及逐月入账/出账。
   */
  @GetMapping("/analysis")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<HolderAnalysis> analysis(@RequestParam("accountHolder") String accountHolder) {
    String holder = accountHolder == null ? "" : accountHolder.trim();
    if (holder.isEmpty()) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "请选择户名");
    }
    List<Long> visibleIds = visibleStatementIds(currentUserId());
    if (visibleIds.isEmpty()) {
      throw new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "暂无可见的流水数据");
    }

    Specification<BankStatement> statementSpec = (root, query, cb) -> root.get("id").in(visibleIds);
    List<BankStatement> statements = bankStatementRepository.findAll(statementSpec).stream()
      .filter(s -> s.getAccountHolder() != null && s.getAccountHolder().trim().equals(holder))
      .toList();
    if (statements.isEmpty()) {
      throw new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "未找到该户名的流水单");
    }

    List<Long> ids = statements.stream().map(BankStatement::getId).toList();
    Specification<BankStatementTransaction> txSpec = (root, query, cb) ->
      root.get("statement").get("id").in(ids);
    List<BankStatementTransaction> txns = transactionRepository.findAll(txSpec);

    Map<Long, List<BankStatementTransaction>> txnsByStatement = new HashMap<>();
    for (BankStatementTransaction t : txns) {
      txnsByStatement.computeIfAbsent(t.getStatement().getId(), k -> new ArrayList<>()).add(t);
    }
    // 同账号上一份流水单期末余额：账期首尾相接时用于承接期初，避免账期起始到首笔交易的空档被丢弃
    Map<Long, CarryIn> carryIns = buildCarryIns(statements, txnsByStatement);

    BigDecimal totalIncome = BigDecimal.ZERO;
    BigDecimal totalExpense = BigDecimal.ZERO;
    TreeMap<YearMonth, BigDecimal[]> monthly = new TreeMap<>();
    Map<YearMonth, Long> monthlyCount = new HashMap<>();
    for (BankStatementTransaction t : txns) {
      BigDecimal amount = nz(t.getAmount());
      boolean internal = "其他".equals(t.getDirection());
      BigDecimal income = internal || amount.signum() < 0 ? BigDecimal.ZERO : amount;
      BigDecimal expense = internal || amount.signum() >= 0 ? BigDecimal.ZERO : amount.abs();
      totalIncome = totalIncome.add(income);
      totalExpense = totalExpense.add(expense);
      if (t.getTransDate() != null) {
        YearMonth month = YearMonth.from(t.getTransDate());
        BigDecimal[] bucket = monthly.computeIfAbsent(month,
          k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        bucket[0] = bucket[0].add(income);
        bucket[1] = bucket[1].add(expense);
        monthlyCount.merge(month, 1L, Long::sum);
      }
    }

    // 月份补齐，避免中间断档
    List<MonthlyFlow> monthlyFlows = new ArrayList<>();
    if (!monthly.isEmpty()) {
      YearMonth cursor = monthly.firstKey();
      YearMonth last = monthly.lastKey();
      while (!cursor.isAfter(last)) {
        BigDecimal[] bucket = monthly.getOrDefault(cursor,
          new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        monthlyFlows.add(new MonthlyFlow(cursor.toString(), bucket[0], bucket[1],
          bucket[0].subtract(bucket[1]), monthlyCount.getOrDefault(cursor, 0L)));
        cursor = cursor.plusMonths(1);
      }
    }

    List<AccountSummary> accounts = statements.stream()
      .sorted(Comparator.comparing((BankStatement s) -> s.getStartDate(),
        Comparator.nullsLast(Comparator.naturalOrder())))
      .map(s -> new AccountSummary(s.getId(), s.getBankName(), s.getBankCode(), s.getAccountNo(),
        s.getStartDate(), s.getEndDate(),
        s.getTransactionCount() == null ? 0 : s.getTransactionCount(),
        nz(s.getTotalIncome()), nz(s.getTotalExpense()),
        buildOverview(s, txnsByStatement.getOrDefault(s.getId(), List.of()),
          carryIns.get(s.getId()))))
      .toList();

    LocalDate startDate = statements.stream().map(BankStatement::getStartDate)
      .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    LocalDate endDate = statements.stream().map(BankStatement::getEndDate)
      .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

    return ResponseEntity.ok(new HolderAnalysis(holder, statements.size(), accounts.size(),
      txns.size(), totalIncome, totalExpense, totalIncome.subtract(totalExpense),
      startDate, endDate, accounts, monthlyFlows));
  }

  /**
   * 往来对手分析：按对方户名归并（仅做基础清洗，不做近似合并），
   * 找出同时存在入账与出账的对手，并按往来总额降序返回。
   *
   * <p>对方户名为空的交易不参与统计，单独返回排除笔数。
   * 自有账户互转：对方户名与己方户名一致，或对方账号命中同一客户的其他可见账户。
   */
  @GetMapping("/counterparties")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<CounterpartyAnalysis> counterparties(
    @RequestParam(value = "accountHolder", required = false) String accountHolder) {
    List<Long> visibleIds = visibleStatementIds(currentUserId());
    if (visibleIds.isEmpty()) {
      return ResponseEntity.ok(new CounterpartyAnalysis(0, 0, 0, 0,
        BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, List.of(), List.of(), List.of()));
    }
    Specification<BankStatement> statementSpec = (root, query, cb) -> root.get("id").in(visibleIds);
    List<BankStatement> statements = bankStatementRepository.findAll(statementSpec);
    // 跟随页面顶部的户名筛选；未选择时统计全部可见流水
    String holderFilter = accountHolder == null ? "" : normalizeCounterpartyName(accountHolder);
    if (!holderFilter.isEmpty()) {
      statements = statements.stream()
        .filter(s -> holderFilter.equals(normalizeCounterpartyName(s.getAccountHolder())))
        .toList();
      if (statements.isEmpty()) {
        return ResponseEntity.ok(new CounterpartyAnalysis(0, 0, 0, 0,
          BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, List.of(), List.of(), List.of()));
      }
    }
    Map<Long, BankStatement> statementById = new HashMap<>();
    // 账号 -> 客户，用于识别自有账户互转
    Map<String, String> accountOwner = new HashMap<>();
    for (BankStatement s : statements) {
      statementById.put(s.getId(), s);
      String accountNo = digitsOnly(s.getAccountNo());
      if (!accountNo.isEmpty()) {
        accountOwner.put(accountNo, normalizeCounterpartyName(s.getAccountHolder()));
      }
    }

    List<Long> scopedIds = statements.stream().map(BankStatement::getId).toList();
    Specification<BankStatementTransaction> txSpec = (root, query, cb) ->
      root.get("statement").get("id").in(scopedIds);
    List<BankStatementTransaction> txns = transactionRepository.findAll(txSpec);

    Map<String, CpAgg> groups = new HashMap<>();
    // 消费型大众对手（抖音/美团/微信转账等）不作为可打标的正常对手展示
    Set<String> consumerKeys = riskDatabaseService.consumerNameKeys();
    long excludedBlank = 0;
    long totalTxn = 0;
    BigDecimal totalIncome = BigDecimal.ZERO;
    BigDecimal totalExpense = BigDecimal.ZERO;
    long selfTransferCount = 0;
    BigDecimal selfTransferAmount = BigDecimal.ZERO;

    for (BankStatementTransaction t : txns) {
      String key = normalizeCounterpartyName(t.getCounterpartyName());
      if (key.isEmpty()) {
        excludedBlank++;
        continue;
      }
      if (consumerKeys.contains(key)) {
        continue;
      }
      String direction = t.getDirection();
      if (!"入".equals(direction) && !"出".equals(direction)) {
        continue;
      }
      BankStatement statement = statementById.get(t.getStatement().getId());
      String ownHolder = statement == null ? "" : normalizeCounterpartyName(statement.getAccountHolder());
      boolean self = isSelfTransfer(t, key, ownHolder, accountOwner);
      BigDecimal amount = nz(t.getAmount()).abs();

      CpAgg agg = groups.computeIfAbsent(key, k -> new CpAgg());
      if (agg.displayName == null) {
        agg.displayName = displayCounterpartyName(t.getCounterpartyName());
      }
      totalTxn++;
      if ("入".equals(direction)) {
        agg.incomeCount++;
        agg.incomeTotal = agg.incomeTotal.add(amount);
        totalIncome = totalIncome.add(amount);
      } else {
        agg.expenseCount++;
        agg.expenseTotal = agg.expenseTotal.add(amount);
        totalExpense = totalExpense.add(amount);
      }
      agg.txnCount++;
      if (statement != null) {
        agg.statementIds.add(statement.getId());
        if (statement.getAccountHolder() != null) {
          agg.holders.add(statement.getAccountHolder().trim());
        }
      }
      if (t.getTransDate() != null && (agg.lastDate == null || t.getTransDate().isAfter(agg.lastDate))) {
        agg.lastDate = t.getTransDate();
      }
      if (self) {
        agg.selfCount++;
        agg.selfAmount = agg.selfAmount.add(amount);
        selfTransferCount++;
        selfTransferAmount = selfTransferAmount.add(amount);
      }
    }

    List<CounterpartyRow> rows = new ArrayList<>();
    int dualCount = 0;
    for (Map.Entry<String, CpAgg> entry : groups.entrySet()) {
      CpAgg agg = entry.getValue();
      if (agg.incomeCount == 0 || agg.expenseCount == 0) {
        continue;
      }
      dualCount++;
      BigDecimal totalFlow = agg.incomeTotal.add(agg.expenseTotal);
      rows.add(new CounterpartyRow(entry.getKey(), agg.displayName,
        agg.incomeCount, agg.incomeTotal, agg.expenseCount, agg.expenseTotal,
        agg.incomeTotal.subtract(agg.expenseTotal), totalFlow,
        agg.lastDate, agg.statementIds.size(), agg.holders.size(),
        agg.txnCount, agg.selfCount, agg.selfAmount));
    }
    rows.sort(Comparator.comparing(CounterpartyRow::totalFlow).reversed()
      .thenComparing(CounterpartyRow::name));

    // 明细筛选用：客户与账户选项
    List<String> holderOptions = statements.stream()
      .map(BankStatement::getAccountHolder)
      .filter(h -> h != null && !h.isBlank())
      .distinct()
      .sorted()
      .toList();
    Map<String, CounterpartyAccountOption> accountOptions = new HashMap<>();
    for (BankStatement s : statements) {
      if (s.getAccountNo() == null || s.getAccountNo().isBlank()) {
        continue;
      }
      accountOptions.putIfAbsent(digitsOnly(s.getAccountNo()),
        new CounterpartyAccountOption(s.getAccountNo(), s.getBankName(), s.getAccountHolder()));
    }
    List<CounterpartyAccountOption> accountList = accountOptions.values().stream()
      .sorted(Comparator.comparing(CounterpartyAccountOption::accountHolder,
          Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(CounterpartyAccountOption::bankName,
          Comparator.nullsLast(Comparator.naturalOrder())))
      .toList();

    return ResponseEntity.ok(new CounterpartyAnalysis(groups.size(), dualCount, totalTxn, excludedBlank,
      totalIncome, totalExpense, selfTransferCount, selfTransferAmount,
      holderOptions, accountList, rows));
  }

  /**
   * 单个对手的逐笔明细，可按客户（户名）、账户、时间范围筛选。
   */
  @GetMapping("/counterparties/detail")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<CounterpartyDetail> counterpartyDetail(
    @RequestParam("name") String name,
    @RequestParam(value = "accountHolder", required = false) String accountHolder,
    @RequestParam(value = "accountNo", required = false) String accountNo,
    @RequestParam(value = "from", required = false) LocalDate from,
    @RequestParam(value = "to", required = false) LocalDate to) {

    String key = normalizeCounterpartyName(name);
    if (key.isEmpty()) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, "请指定对手名称");
    }
    List<Long> visibleIds = visibleStatementIds(currentUserId());
    if (visibleIds.isEmpty()) {
      return ResponseEntity.ok(new CounterpartyDetail(key, name, 0, BigDecimal.ZERO,
        0, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO, List.of()));
    }
    Specification<BankStatement> statementSpec = (root, query, cb) -> root.get("id").in(visibleIds);
    List<BankStatement> statements = bankStatementRepository.findAll(statementSpec);
    Map<Long, BankStatement> statementById = new HashMap<>();
    Map<String, String> accountOwner = new HashMap<>();
    for (BankStatement s : statements) {
      statementById.put(s.getId(), s);
      String no = digitsOnly(s.getAccountNo());
      if (!no.isEmpty()) {
        accountOwner.put(no, normalizeCounterpartyName(s.getAccountHolder()));
      }
    }

    Specification<BankStatementTransaction> txSpec = (root, query, cb) ->
      root.get("statement").get("id").in(visibleIds);
    List<BankStatementTransaction> txns = transactionRepository.findAll(txSpec);

    String holderFilter = accountHolder == null ? "" : normalizeCounterpartyName(accountHolder);
    String accountFilter = digitsOnly(accountNo);
    List<CounterpartyTxn> items = new ArrayList<>();
    int incomeCount = 0;
    int expenseCount = 0;
    BigDecimal incomeTotal = BigDecimal.ZERO;
    BigDecimal expenseTotal = BigDecimal.ZERO;
    long selfCount = 0;
    BigDecimal selfAmount = BigDecimal.ZERO;
    String displayName = null;

    for (BankStatementTransaction t : txns) {
      if (!key.equals(normalizeCounterpartyName(t.getCounterpartyName()))) {
        continue;
      }
      String direction = t.getDirection();
      if (!"入".equals(direction) && !"出".equals(direction)) {
        continue;
      }
      BankStatement statement = statementById.get(t.getStatement().getId());
      if (statement == null) {
        continue;
      }
      String ownHolder = normalizeCounterpartyName(statement.getAccountHolder());
      if (!holderFilter.isEmpty() && !holderFilter.equals(ownHolder)) {
        continue;
      }
      if (!accountFilter.isEmpty() && !accountFilter.equals(digitsOnly(statement.getAccountNo()))) {
        continue;
      }
      if (from != null && (t.getTransDate() == null || t.getTransDate().isBefore(from))) {
        continue;
      }
      if (to != null && (t.getTransDate() == null || t.getTransDate().isAfter(to))) {
        continue;
      }
      boolean self = isSelfTransfer(t, key, ownHolder, accountOwner);
      BigDecimal amount = nz(t.getAmount()).abs();
      BigDecimal signed = "入".equals(direction) ? amount : amount.negate();
      if ("入".equals(direction)) {
        incomeCount++;
        incomeTotal = incomeTotal.add(amount);
      } else {
        expenseCount++;
        expenseTotal = expenseTotal.add(amount);
      }
      if (self) {
        selfCount++;
        selfAmount = selfAmount.add(amount);
      }
      if (displayName == null) {
        displayName = displayCounterpartyName(t.getCounterpartyName());
      }
      items.add(new CounterpartyTxn(t.getId(), statement.getId(), t.getTransDate(), direction,
        signed, t.getSummary(), statement.getBankName(), statement.getAccountNo(),
        statement.getAccountHolder(), self));
    }
    items.sort(Comparator.comparing(CounterpartyTxn::transDate,
        Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(CounterpartyTxn::id));

    int incomeFinal = incomeCount;
    int expenseFinal = expenseCount;
    BigDecimal incomeSum = incomeTotal;
    BigDecimal expenseSum = expenseTotal;
    long selfFinal = selfCount;
    BigDecimal selfSum = selfAmount;
    return ResponseEntity.ok(new CounterpartyDetail(key,
      displayName == null ? name.trim() : displayName,
      incomeFinal, incomeSum, expenseFinal, expenseSum,
      incomeSum.subtract(expenseSum), selfFinal, selfSum, items));
  }

  /**
   * 对手户名清洗：去掉所有空白（含全角空格），英文转小写；不做近似合并。
   */
  private static String normalizeCounterpartyName(String raw) {
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

  /**
   * 展示用对手名：去首尾空白并把连续空白压成一个空格。
   */
  private static String displayCounterpartyName(String raw) {
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

  /**
   * 自有账户互转：对方户名等于己方户名，或对方账号属于同一客户的其他可见账户。
   */
  private static boolean isSelfTransfer(BankStatementTransaction t, String normalizedName,
                                        String ownHolder, Map<String, String> accountOwner) {
    if (!ownHolder.isEmpty() && ownHolder.equals(normalizedName)) {
      return true;
    }
    String cpAccount = digitsOnly(t.getCounterpartyAccount());
    if (cpAccount.isEmpty()) {
      return false;
    }
    String owner = accountOwner.get(cpAccount);
    return owner != null && owner.equals(ownHolder);
  }

  /**
   * 对手归并累加器。
   */
  private static final class CpAgg {
    private String displayName;
    private int incomeCount;
    private BigDecimal incomeTotal = BigDecimal.ZERO;
    private int expenseCount;
    private BigDecimal expenseTotal = BigDecimal.ZERO;
    private LocalDate lastDate;
    private final Set<Long> statementIds = new HashSet<>();
    private final Set<String> holders = new HashSet<>();
    private long txnCount;
    private long selfCount;
    private BigDecimal selfAmount = BigDecimal.ZERO;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * 交易排序：日期、时间、序号、id。
   */
  private static Comparator<BankStatementTransaction> txnOrder() {
    return Comparator
      .comparing(BankStatementTransaction::getTransDate, Comparator.nullsLast(Comparator.naturalOrder()))
      .thenComparing(t -> t.getTransTime() == null ? "" : t.getTransTime())
      .thenComparing(t -> t.getSeqNo() == null ? Integer.MAX_VALUE : t.getSeqNo())
      .thenComparing(BankStatementTransaction::getId);
  }

  /**
   * 计算同账号、账期首尾相接的流水单可承接的期初余额。
   *
   * <p>仅当上一份流水单的账期结束日 + 1 天 = 本份账期起始日，且有余额数据时才承接；
   * 上一份流水单末笔交易之后到账期结束的余额按最后余额延续处理。
   */
  private static Map<Long, CarryIn> buildCarryIns(List<BankStatement> statements,
                                                  Map<Long, List<BankStatementTransaction>> txnsByStatement) {
    Map<String, List<BankStatement>> byAccount = new HashMap<>();
    for (BankStatement s : statements) {
      String accountNo = s.getAccountNo() == null ? "" : s.getAccountNo().trim();
      if (accountNo.isEmpty() || s.getStartDate() == null || s.getEndDate() == null) {
        continue;
      }
      byAccount.computeIfAbsent(accountNo, k -> new ArrayList<>()).add(s);
    }
    Map<Long, CarryIn> result = new HashMap<>();
    for (List<BankStatement> group : byAccount.values()) {
      group.sort(Comparator.comparing(BankStatement::getStartDate));
      for (int i = 1; i < group.size(); i++) {
        BankStatement prev = group.get(i - 1);
        BankStatement cur = group.get(i);
        if (!prev.getEndDate().plusDays(1).equals(cur.getStartDate())) {
          continue;
        }
        BigDecimal closing = closingBalanceOf(txnsByStatement.getOrDefault(prev.getId(), List.of()));
        if (closing == null) {
          continue;
        }
        result.put(cur.getId(), new CarryIn(closing, cur.getStartDate(), prev.getEndDate()));
      }
    }
    return result;
  }

  /**
   * 末笔有余额交易的余额；无余额数据时返回 null。
   */
  private static BigDecimal closingBalanceOf(List<BankStatementTransaction> source) {
    return source.stream()
      .filter(t -> t.getBalance() != null && t.getTransDate() != null)
      .max(txnOrder())
      .map(BankStatementTransaction::getBalance)
      .orElse(null);
  }

  /**
   * 账户余额概览。
   *
   * <p>日均余额口径：
   * <ul>
   *   <li>统计区间优先从账期起始开始；同账号上一份流水单账期首尾相接时，期初余额承接其期末余额，
   *       空档天数按该余额计入</li>
   *   <li>无法承接时从「首笔有余额交易日期」开始；账期起始若更早，因期初余额未知，前置天数不计入分母</li>
   *   <li>结束日取账期结束日与末笔交易日的较晚者；末日之后无交易时，按最后日末余额延续</li>
   *   <li>日末余额 = 当天最后一笔有余额交易的余额；当天无交易则沿用前一日的日末余额</li>
   *   <li>日均余额 = Σ(每日日末余额) / 统计天数，保留 2 位小数</li>
   * </ul>
   */
  private static AccountBalanceOverview buildOverview(BankStatement statement,
                                                      List<BankStatementTransaction> source,
                                                      CarryIn carryIn) {
    List<BankStatementTransaction> sorted = new ArrayList<>(source);
    sorted.sort(txnOrder());


    // 结息：摘要/对方含结息或利息；入账计入结息收入，出账记为利息支出/扣息
    List<InterestItem> interests = new ArrayList<>();
    List<InterestItem> interestExpenses = new ArrayList<>();
    BigDecimal interestTotal = BigDecimal.ZERO;
    BigDecimal interestExpenseTotal = BigDecimal.ZERO;
    for (BankStatementTransaction t : sorted) {
      if (!isInterestText(t)) {
        continue;
      }
      BigDecimal amount = nz(t.getAmount());
      String direction = t.getDirection() == null
        ? (amount.signum() >= 0 ? "入" : "出") : t.getDirection();
      if ("其他".equals(direction)) {
        continue;
      }
      if ("入".equals(direction)) {
        if (!isInterestIncome(t)) {
          continue;
        }
        BigDecimal income = amount.abs();
        interestTotal = interestTotal.add(income);
        interests.add(new InterestItem(t.getId(), t.getTransDate(), income, direction,
          t.getCounterpartyName(), t.getSummary()));
      } else {
        if (!isInterestExpense(t)) {
          continue;
        }
        BigDecimal expense = amount.abs();
        interestExpenseTotal = interestExpenseTotal.add(expense);
        interestExpenses.add(new InterestItem(t.getId(), t.getTransDate(), expense.negate(), direction,
          t.getCounterpartyName(), t.getSummary()));
      }
    }

    List<BankStatementTransaction> withBalance = sorted.stream()
      .filter(t -> t.getBalance() != null && t.getTransDate() != null)
      .toList();
    if (withBalance.isEmpty()) {
      return new AccountBalanceOverview(false,
        null, null, null, 0, null, null, null, null, null, null,
        null,
        "该流水单无余额列，无法计算日均余额和最高余额；结息仍按摘要/对方识别",
        interests.size(), interestTotal, interests,
        interestExpenses.size(), interestExpenseTotal, interestExpenses);
    }

    BankStatementTransaction first = withBalance.get(0);
    BankStatementTransaction last = withBalance.get(withBalance.size() - 1);
    LocalDate firstTxnDate = first.getTransDate();
    LocalDate statStart = firstTxnDate;
    // 无上期可承接时期初余额 = 首笔交易后余额 - 首笔金额；可承接时为上一份流水单期末余额
    BigDecimal openingBalance = first.getBalance().subtract(nz(first.getAmount()));
    if (carryIn != null) {
      statStart = carryIn.fromDate().isBefore(statStart) ? carryIn.fromDate() : statStart;
      openingBalance = carryIn.openingBalance();
    }
    LocalDate statEnd = statement.getEndDate() != null && statement.getEndDate().isAfter(last.getTransDate())
      ? statement.getEndDate() : last.getTransDate();
    long statDays = ChronoUnit.DAYS.between(statStart, statEnd) + 1;
    BigDecimal closingBalance = last.getBalance();

    // 每个交易日的日末余额（当天最后一笔有余额交易）
    TreeMap<LocalDate, BigDecimal> dayEndBalances = new TreeMap<>();
    for (BankStatementTransaction t : withBalance) {
      dayEndBalances.put(t.getTransDate(), t.getBalance());
    }

    BigDecimal sum = BigDecimal.ZERO;
    BigDecimal current = openingBalance;
    for (LocalDate date = statStart; !date.isAfter(statEnd); date = date.plusDays(1)) {
      BigDecimal dayEnd = dayEndBalances.get(date);
      if (dayEnd != null) {
        current = dayEnd;
      }
      sum = sum.add(current);
    }
    BigDecimal avgDailyBalance = sum.divide(BigDecimal.valueOf(statDays), 2, RoundingMode.HALF_UP);

    // 最高余额：期初余额也是候选；与交易余额相等时优先展示交易
    BigDecimal maxBalance = openingBalance;
    LocalDate maxDate = statStart;
    String maxSource = "OPENING";
    BankStatementTransaction maxTxn = null;
    for (BankStatementTransaction t : withBalance) {
      if (t.getBalance().compareTo(maxBalance) >= 0) {
        maxBalance = t.getBalance();
        maxDate = t.getTransDate();
        maxSource = "TRANSACTION";
        maxTxn = t;
      }
    }
    MaxBalanceTxn maxBalanceTxn = maxTxn == null ? null : new MaxBalanceTxn(maxTxn.getId(),
      maxTxn.getTransDate(), maxTxn.getAmount(), maxTxn.getDirection(),
      maxTxn.getCounterpartyName(), maxTxn.getSummary(), maxTxn.getRemark());

    StringBuilder note = new StringBuilder();
    if (carryIn != null) {
      long gap = ChronoUnit.DAYS.between(statStart, firstTxnDate);
      note.append("期初余额承接上一份流水单（").append(carryIn.prevEndDate()).append(" 期末 ")
        .append(carryIn.openingBalance().toPlainString()).append("）");
      if (gap > 0) {
        note.append("，账期起始至首笔交易日 ").append(gap).append(" 天按该余额计入");
      }
      note.append("；");
    } else if (statement.getStartDate() != null && statement.getStartDate().isBefore(statStart)) {
      long gap = ChronoUnit.DAYS.between(statement.getStartDate(), statStart);
      note.append("账期起始早于首笔有余额交易日，前置 ").append(gap).append(" 天因期初余额未知未计入；");
    }
    if (statement.getEndDate() != null && statement.getEndDate().isAfter(last.getTransDate())) {
      note.append("末笔交易后至账期结束按最后余额延续；");
    }
    if (withBalance.size() < sorted.size()) {
      note.append("有 ").append(sorted.size() - withBalance.size())
        .append(" 笔交易缺少余额或日期，未参与日末余额计算；");
    }
    note.append(carryIn == null ? "期初余额 = 首笔交易后余额 - 首笔金额" : "期初余额取上一份流水单期末余额");

    String rangeBasis = carryIn == null
      ? "首笔有余额交易日至账期结束（末日无交易按最后余额延续）"
      : "账期起始至账期结束（期初余额承接上一份流水单，末日无交易按最后余额延续）";
    return new AccountBalanceOverview(true, rangeBasis,
      statStart, statEnd, statDays, openingBalance, closingBalance, avgDailyBalance,
      maxBalance, maxDate, maxSource, maxBalanceTxn, note.toString(),
      interests.size(), interestTotal, interests,
      interestExpenses.size(), interestExpenseTotal, interestExpenses);
  }

  /**
   * 摘要或对方是否含结息/利息字样。
   */
  private static boolean isInterestText(BankStatementTransaction t) {
    String summary = t.getSummary() == null ? "" : t.getSummary();
    String counterparty = t.getCounterpartyName() == null ? "" : t.getCounterpartyName();
    return summary.contains("结息") || summary.contains("利息")
      || counterparty.contains("结息") || counterparty.contains("利息");
  }

  /**
   * 是否为利息入账：方向为入，排除保证金扣款等非利息入账。
   */
  private static boolean isInterestIncome(BankStatementTransaction t) {
    if (!isInterestText(t)) {
      return false;
    }
    String summary = t.getSummary() == null ? "" : t.getSummary();
    boolean incoming = "入".equals(t.getDirection())
      || (t.getDirection() == null && nz(t.getAmount()).signum() > 0);
    if (!incoming) {
      return false;
    }
    // 保证金扣款入账等虽含“利息”字样，但不是利息收入
    return !summary.contains("保证金") && !summary.contains("扣款");
  }

  /**
   * 是否为利息支出/扣息：方向为出，排除保证金类扣款。
   */
  private static boolean isInterestExpense(BankStatementTransaction t) {
    if (!isInterestText(t)) {
      return false;
    }
    String summary = t.getSummary() == null ? "" : t.getSummary();
    boolean outgoing = "出".equals(t.getDirection())
      || (t.getDirection() == null && nz(t.getAmount()).signum() < 0);
    return outgoing && !summary.contains("保证金");
  }

  /**
   * 当前用户可见的流水单 id（组织子树内 + 被分享）。
   */
  private List<Long> visibleStatementIds(Long userId) {
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
    return bankStatementRepository.findAll(spec).stream().map(BankStatement::getId).toList();
  }

  /**
   * 按摘要关键字归类，用于首页「流水构成占比」。
   */
  private static String classifySummary(String summary) {
    if (summary == null || summary.isBlank()) {
      return "其他";
    }
    String s = summary;
    if (s.contains("工资") || s.contains("薪")) {
      return "工资";
    }
    if (s.contains("利息")) {
      return "利息";
    }
    if (s.contains("手续费") || s.contains("费用") || s.contains("服务费")) {
      return "手续费";
    }
    if (s.contains("转账") || s.contains("汇款") || s.contains("转出")
      || s.contains("转入") || s.contains("跨行") || s.contains("汇入") || s.contains("汇出")) {
      return "转账";
    }
    if (s.contains("消费") || s.contains("购物") || s.contains("支付")
      || s.contains("刷卡") || s.contains("快捷")) {
      return "消费";
    }
    return "其他";
  }

  /**
   * 大额/异常交易阈值
   */
  private static final BigDecimal ANOMALY_THRESHOLD = new BigDecimal("50000");

  public record DashboardStats(
    long totalCount,
    BigDecimal totalIncome,
    BigDecimal totalExpense,
    long todayCount,
    long anomalyCount,
    BigDecimal anomalyThreshold,
    List<DailyPoint> daily,
    List<CategoryStat> categories,
    List<CounterpartyStat> topCounterparties,
    List<LargeItem> recentLarge) {
  }

  public record DailyPoint(String date, BigDecimal income, BigDecimal expense) {
  }

  public record CategoryStat(String name, BigDecimal amount, double percent) {
  }

  public record CounterpartyStat(String name, BigDecimal amount) {
  }

  public record LargeItem(Long id, Long statementId, LocalDate transDate, BigDecimal amount,
                          String counterpartyName, String summary) {
  }

  public record AccountHolderOption(String accountHolder, int statementCount, int accountCount) {
  }

  public record AccountSummary(Long id, String bankName, String bankCode, String accountNo,
                               LocalDate startDate, LocalDate endDate, int transactionCount,
                               BigDecimal totalIncome, BigDecimal totalExpense,
                               AccountBalanceOverview overview) {
  }

  /**
   * 账户余额概览：日均、最高、期初期末与结息。
   */
  public record AccountBalanceOverview(boolean balanceAvailable, String rangeBasis,
                                       LocalDate statStart, LocalDate statEnd, long statDays,
                                       BigDecimal openingBalance, BigDecimal closingBalance,
                                       BigDecimal avgDailyBalance,
                                       BigDecimal maxBalance, LocalDate maxBalanceDate,
                                       String maxBalanceSource, MaxBalanceTxn maxBalanceTxn,
                                       String note,
                                       long interestCount, BigDecimal interestTotal,
                                       List<InterestItem> interests,
                                       long interestExpenseCount, BigDecimal interestExpenseTotal,
                                       List<InterestItem> interestExpenses) {
  }

  /**
   * 跨期承接信息：上一份流水单期末余额与本期账期起始日。
   */
  private record CarryIn(BigDecimal openingBalance, LocalDate fromDate, LocalDate prevEndDate) {
  }

  public record MaxBalanceTxn(Long transactionId, LocalDate transDate, BigDecimal amount,
                              String direction, String counterpartyName, String summary,
                              String remark) {
  }

  public record InterestItem(Long transactionId, LocalDate transDate, BigDecimal amount,
                             String direction, String counterpartyName, String summary) {
  }

  public record MonthlyFlow(String month, BigDecimal income, BigDecimal expense,
                            BigDecimal net, long count) {
  }

  public record HolderAnalysis(String accountHolder, int statementCount, int accountCount,
                               long transactionCount, BigDecimal totalIncome,
                               BigDecimal totalExpense, BigDecimal netAmount,
                               LocalDate startDate, LocalDate endDate,
                               List<AccountSummary> accounts, List<MonthlyFlow> monthly) {
  }

  /**
   * 往来对手分析结果：总对手数、双向对手数、空户名排除笔数与明细行。
   */
  public record CounterpartyAnalysis(int counterpartyCount, int dualCount, long transactionCount,
                                     long excludedBlankCount, BigDecimal totalIncome,
                                     BigDecimal totalExpense, long selfTransferCount,
                                     BigDecimal selfTransferAmount, List<String> holders,
                                     List<CounterpartyAccountOption> accounts,
                                     List<CounterpartyRow> items) {
  }

  public record CounterpartyAccountOption(String accountNo, String bankName, String accountHolder) {
  }

  public record CounterpartyRow(String name, String displayName,
                                int incomeCount, BigDecimal incomeTotal,
                                int expenseCount, BigDecimal expenseTotal,
                                BigDecimal netAmount, BigDecimal totalFlow,
                                LocalDate lastDate, int accountCount, int holderCount,
                                long transactionCount, long selfTransferCount,
                                BigDecimal selfTransferAmount) {
  }

  /**
   * 单个对手的逐笔明细（可按客户、账户、时间范围筛选）。
   */
  public record CounterpartyDetail(String name, String displayName,
                                   int incomeCount, BigDecimal incomeTotal,
                                   int expenseCount, BigDecimal expenseTotal,
                                   BigDecimal netAmount, long selfTransferCount,
                                   BigDecimal selfTransferAmount, List<CounterpartyTxn> items) {
  }

  public record CounterpartyTxn(Long id, Long statementId, LocalDate transDate, String direction,
                                BigDecimal amount, String summary, String bankName,
                                String accountNo, String accountHolder, boolean selfTransfer) {
  }

  /**
   * 交易对手风险警示：按当前页行或整份流水单/户名范围，比对资方库与黑名单。
   *
   * <p>scope=PAGE 时只比对 items 里的当前页行；STATEMENT/HOLDER/ALL 额外返回整段范围的汇总。
   * 一次请求完成批量比对，避免前端逐行查询。
   */
  @PostMapping("/risk-alerts")
  @RequiresPermissions("bankStatement:view")
  public ResponseEntity<RiskAlertService.AlertResult> riskAlerts(
    @RequestBody RiskAlertService.AlertRequest request) {
    return ResponseEntity.ok(riskAlertService.analyze(currentUserId(), request));
  }

  /**
   * 流水单详情。
   */
  @GetMapping("/{id}")
  @RequiresPermissions("bankStatement:view")
  public ResponseEntity<BankStatement> detail(@PathVariable Long id) {
    BankStatement statement = bankStatementRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
    scopeService.requireVisible(currentUserId(), statement);
    return ResponseEntity.ok(statement);
  }

  /**
   * 某流水单的交易明细分页。
   */
  @GetMapping("/{id}/transactions")
  @RequiresPermissions("bankStatement:transaction:view")
  public ResponseEntity<Page<BankStatementTransaction>> transactions(
    @PathVariable Long id,
    @RequestParam(value = "direction", required = false) String direction,
    @RequestParam(value = "transDateFrom", required = false) LocalDate transDateFrom,
    @RequestParam(value = "transDateTo", required = false) LocalDate transDateTo,
    @RequestParam(value = "minAmount", required = false) BigDecimal minAmount,
    @RequestParam(value = "maxAmount", required = false) BigDecimal maxAmount,
    @RequestParam(value = "keyword", required = false) String keyword,
    @RequestParam(value = "page", defaultValue = "1") int page,
    @RequestParam(value = "size", defaultValue = "50") int size) {

    if (!bankStatementRepository.existsById(id)) {
      throw new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在");
    }
    scopeService.requireVisible(currentUserId(),
      bankStatementRepository.getReferenceById(id));

    Specification<BankStatementTransaction> spec = (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(root.get("statement").get("id"), id));
      if (direction != null && !direction.isBlank()) {
        predicates.add(cb.equal(root.get("direction"), direction));
      }
      if (transDateFrom != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("transDate"), transDateFrom));
      }
      if (transDateTo != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("transDate"), transDateTo));
      }
      if (minAmount != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("amount"), minAmount));
      }
      if (maxAmount != null) {
        predicates.add(cb.lessThanOrEqualTo(root.get("amount"), maxAmount));
      }
      if (keyword != null && !keyword.isBlank()) {
        String like = "%" + keyword + "%";
        predicates.add(cb.or(
          cb.like(root.get("counterpartyName"), like),
          cb.like(root.get("counterpartyAccount"), like),
          cb.like(root.get("summary"), like),
          cb.like(root.get("purpose"), like),
          cb.like(root.get("postscript"), like),
          cb.like(root.get("remark"), like)
        ));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
    Pageable pageable = PageRequest.of(Math.max(page - 1, 0), size, Sort.by(Sort.Direction.ASC, "seqNo"));
    return ResponseEntity.ok(transactionRepository.findAll(spec, pageable));
  }

  /**
   * 导出某流水单的全部交易明细为 CSV。
   */
  @GetMapping(value = "/{id}/transactions/export", produces = "text/csv;charset=UTF-8")
  @RequiresPermissions("bankStatement:transaction:export")
  public ResponseEntity<byte[]> exportTransactions(@PathVariable Long id) {
    BankStatement statement = bankStatementRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
    scopeService.requireVisible(currentUserId(), statement);

    Specification<BankStatementTransaction> spec = (root, query, cb) ->
      cb.equal(root.get("statement").get("id"), id);
    List<BankStatementTransaction> list = transactionRepository.findAll(spec, Sort.by(Sort.Direction.ASC, "seqNo"));

    StringBuilder sb = new StringBuilder();
    // UTF-8 BOM，避免 Excel 打开乱码
    sb.append('\uFEFF');
    sb.append("序号,交易日期,交易时间,金额,余额,借贷标志,原始方向,对方账号,对方户名,对方户名标签,风险标记,渠道,摘要,用途,附言,备注\n");
    // 对手标签按名称批量取一次，导出不逐行查库
    java.util.Set<String> counterpartyNames = new java.util.LinkedHashSet<>();
    for (BankStatementTransaction t : list) {
      if (t.getCounterpartyName() != null && !t.getCounterpartyName().isBlank()) {
        counterpartyNames.add(t.getCounterpartyName());
      }
    }
    Map<String, List<RiskDatabaseService.TagView>> tagMap = counterpartyNames.isEmpty()
      ? Map.of()
      : riskDatabaseService.tagsByNames(RiskTag.SUBJECT_COUNTERPARTY, counterpartyNames, currentUserId());
    // 风险标记：资方/黑名单，一次构建索引后逐行比对
    Map<Long, String> riskLabels = riskAlertService.riskLabels(currentUserId(), list);
    for (BankStatementTransaction t : list) {
      String tagText = tagMap.getOrDefault(
          RiskDatabaseService.normalize(t.getCounterpartyName()), List.of())
        .stream().map(RiskDatabaseService.TagView::name)
        .collect(java.util.stream.Collectors.joining(";"));
      sb.append(csv(t.getSeqNo())).append(',')
        .append(csv(t.getTransDate())).append(',')
        .append(csv(t.getTransTime())).append(',')
        .append(csv(t.getAmount())).append(',')
        .append(csv(t.getBalance())).append(',')
        .append(csv(t.getDirection())).append(',')
        .append(csv(t.getRawDirection())).append(',')
        .append(csv(t.getCounterpartyAccount())).append(',')
        .append(csv(t.getCounterpartyName())).append(',')
        .append(csv(tagText)).append(',')
        .append(csv(riskLabels.get(t.getId()))).append(',')
        .append(csv(t.getChannel())).append(',')
        .append(csv(t.getSummary())).append(',')
        .append(csv(t.getPurpose())).append(',')
        .append(csv(t.getPostscript())).append(',')
        .append(csv(t.getRemark())).append('\n');
    }

    String fileName = URLEncoder.encode(
      statement.getBankName() + "_" + statement.getAccountNo() + "_明细.csv",
      StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
      .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
      .body(sb.toString().getBytes(StandardCharsets.UTF_8));
  }

  /**
   * 下载流水单对应的原始 PDF 文件。
   */
  @GetMapping("/{id}/source-file")
  @RequiresPermissions("bankStatement:download")
  public ResponseEntity<Resource> downloadSourceFile(@PathVariable Long id) {
    BankStatement statement = bankStatementRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
    scopeService.requireVisible(currentUserId(), statement);
    String key = statement.getSourceFileKey();
    if (key == null || key.isBlank()) {
      throw new BusinessException(CommonResultStatus.FAIL, "该流水单未保存原始文件");
    }
    StorageFile storageFile = storageService.getByKey(key);
    if (storageFile == null) {
      throw new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "原始文件不存在");
    }
    Resource resource = storageService.loadAsResource(key);
    if (resource == null) {
      throw new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "原始文件不存在");
    }
    MediaType mediaType = MediaType.parseMediaType(
      storageFile.getType() == null ? "application/pdf" : storageFile.getType());
    String fileName = URLEncoder.encode(
      statement.getSourceFileName() != null ? statement.getSourceFileName() : "statement.pdf",
      StandardCharsets.UTF_8).replace("+", "%20");
    return ResponseEntity.ok()
      .contentType(mediaType)
      .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
      .body(resource);
  }

  private String csv(Object value) {
    if (value == null) return "";
    String s = value.toString();
    if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
      s = "\"" + s.replace("\"", "\"\"") + "\"";
    }
    return s;
  }

  // ==================== 数据共享 ====================

  /**
   * 可选的分享目标用户列表（供分享弹窗下拉）。
   */
  @GetMapping("/share-users")
  @RequiresPermissions("bankStatement:share")
  public ResponseEntity<List<BankStatementScopeService.ShareUser>> shareUsers() {
    return ResponseEntity.ok(scopeService.listShareUsers());
  }

  /**
   * 某流水单当前已分享给哪些用户。
   */
  @GetMapping("/{id}/shares")
  @RequiresPermissions("bankStatement:share")
  public ResponseEntity<List<BankStatementShare>> shares(@PathVariable Long id) {
    BankStatement statement = bankStatementRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
    scopeService.requireVisible(currentUserId(), statement);
    return ResponseEntity.ok(shareRepository.findByStatementId(id));
  }

  /**
   * 把流水单分享给指定用户（幂等）。
   */
  public record ShareRequest(Long userId) {
  }

  @PostMapping("/{id}/share")
  @RequiresPermissions("bankStatement:share")
  public ResponseEntity<?> shareToUser(@PathVariable Long id, @RequestBody ShareRequest request) {
    if (request.userId() == null) {
      throw new BusinessException(CommonResultStatus.FAIL, "请选择要分享给的用户");
    }
    BankStatement statement = bankStatementRepository.findById(id)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "流水单不存在"));
    scopeService.requireVisible(currentUserId(), statement);

    User grantee = userRepository.findById(request.userId())
      .orElseThrow(() -> new BusinessException(CommonResultStatus.RECORD_NOT_EXIST, "用户不存在"));
    if (shareRepository.existsByStatementIdAndGranteeUserId(id, grantee.getId())) {
      return ResponseEntity.ok().build();
    }
    UserinfoDTO currentUser = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    BankStatementShare share = new BankStatementShare();
    share.setStatementId(id);
    share.setGranteeUserId(grantee.getId());
    share.setGranteeName(grantee.getUsername());
    share.setGrantedByUserId(currentUser == null ? null : currentUser.userId());
    share.setGrantedByName(currentUser == null ? null : currentUser.username());
    share.setCreateTime(LocalDateTime.now());
    shareRepository.save(share);
    return ResponseEntity.ok().build();
  }

  /**
   * 取消分享：删除某用户对该流水单的共享。
   */
  @DeleteMapping("/{id}/share/{granteeUserId}")
  @RequiresPermissions("bankStatement:share")
  public ResponseEntity<?> revokeShare(@PathVariable Long id, @PathVariable Long granteeUserId) {
    shareRepository.deleteByStatementIdAndGranteeUserId(id, granteeUserId);
    return ResponseEntity.ok().build();
  }
}
