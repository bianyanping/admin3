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
import tech.wetech.admin3.sys.model.StorageFile;
import tech.wetech.admin3.sys.model.User;
import tech.wetech.admin3.sys.repository.UserRepository;
import tech.wetech.admin3.sys.service.StorageService;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

  public BankStatementController(BankStatementRepository bankStatementRepository,
                                 BankStatementTransactionRepository transactionRepository,
                                 StorageService storageService,
                                 BankStatementScopeService scopeService,
                                 BankStatementShareRepository shareRepository,
                                 UserRepository userRepository) {
    this.bankStatementRepository = bankStatementRepository;
    this.transactionRepository = transactionRepository;
    this.storageService = storageService;
    this.scopeService = scopeService;
    this.shareRepository = shareRepository;
    this.userRepository = userRepository;
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
      if (amount.signum() >= 0) {
        totalIncome = totalIncome.add(amount);
      } else {
        totalExpense = totalExpense.add(amount.abs());
      }
      if (to.equals(t.getTransDate())) {
        todayCount++;
      }
      if (amount.abs().compareTo(ANOMALY_THRESHOLD) >= 0) {
        anomalyCount++;
        large.add(t);
      }
      if (t.getTransDate() != null) {
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

    BigDecimal totalIncome = BigDecimal.ZERO;
    BigDecimal totalExpense = BigDecimal.ZERO;
    TreeMap<YearMonth, BigDecimal[]> monthly = new TreeMap<>();
    Map<YearMonth, Long> monthlyCount = new HashMap<>();
    for (BankStatementTransaction t : txns) {
      BigDecimal amount = nz(t.getAmount());
      BigDecimal income = amount.signum() >= 0 ? amount : BigDecimal.ZERO;
      BigDecimal expense = amount.signum() < 0 ? amount.abs() : BigDecimal.ZERO;
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
        nz(s.getTotalIncome()), nz(s.getTotalExpense())))
      .toList();

    LocalDate startDate = statements.stream().map(BankStatement::getStartDate)
      .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
    LocalDate endDate = statements.stream().map(BankStatement::getEndDate)
      .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

    return ResponseEntity.ok(new HolderAnalysis(holder, statements.size(), accounts.size(),
      txns.size(), totalIncome, totalExpense, totalIncome.subtract(totalExpense),
      startDate, endDate, accounts, monthlyFlows));
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
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
                               BigDecimal totalIncome, BigDecimal totalExpense) {
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
    sb.append("序号,交易日期,交易时间,金额,余额,借贷标志,对方账号,对方户名,渠道,摘要,用途,附言,备注\n");
    for (BankStatementTransaction t : list) {
      sb.append(csv(t.getSeqNo())).append(',')
        .append(csv(t.getTransDate())).append(',')
        .append(csv(t.getTransTime())).append(',')
        .append(csv(t.getAmount())).append(',')
        .append(csv(t.getBalance())).append(',')
        .append(csv(t.getDirection())).append(',')
        .append(csv(t.getCounterpartyAccount())).append(',')
        .append(csv(t.getCounterpartyName())).append(',')
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
