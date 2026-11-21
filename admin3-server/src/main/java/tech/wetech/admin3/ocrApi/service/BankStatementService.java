package tech.wetech.admin3.ocrApi.service;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.DTO.StatementHeader;
import tech.wetech.admin3.DTO.StatementSummary;
import tech.wetech.admin3.DTO.TransactionDTO;
import tech.wetech.admin3.ocrApi.model.BankStatement;
import tech.wetech.admin3.ocrApi.model.BankStatementTransaction;
import tech.wetech.admin3.ocrApi.repository.BankStatementRepository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 银行流水入库服务：把解析后的 {@link BankStatementDTO} 转成实体并保存。
 */
@Repository
public class BankStatementService {

  private final BankStatementRepository bankStatementRepository;

  public BankStatementService(BankStatementRepository bankStatementRepository) {
    this.bankStatementRepository = bankStatementRepository;
  }

  @Transactional
  public BankStatement importStatement(String bankCode, BankStatementDTO dto,
                                       String sourceFileName, String sourceFileKey,
                                       Long orgId, String orgName) {
    // ========== 同一银行+账号+起止日期：可能是同一份流水的另一页 ==========
    StatementHeader header = dto.getHeader();
    Optional<BankStatement> existing = findExisting(bankCode, header);
    if (existing.isPresent()) {
      // 不直接跳过：按交易指纹合并，重复页不新增，不同页补齐明细
      BankStatement merged = mergeInto(existing.get(), dto);
      System.out.println("  [同账期合并] " + bankCode + " 账号=" + merged.getAccountNo()
        + " 日期=" + header.getStartDate() + "~" + header.getEndDate()
        + " 合并到 id=" + merged.getId() + "，明细总数=" + merged.getTransactionCount());
      return merged;
    }

    // ========== 跨期合并：同银行 + 同账号 + 账期有重叠 ==========
    Optional<BankStatement> overlapping = findOverlapping(bankCode, header);
    if (overlapping.isPresent()) {
      BankStatement merged = mergeInto(overlapping.get(), dto);
      System.out.println("  [跨期合并] " + bankCode + " 账号=" + merged.getAccountNo()
        + " 账期=" + merged.getStartDate() + "~" + merged.getEndDate()
        + " 合并到 id=" + merged.getId() + "，明细总数=" + merged.getTransactionCount());
      return merged;
    }

    BankStatement statement = new BankStatement();
    statement.setBankCode(bankCode);

    if (header != null) {
      statement.setBankName(header.getBankName());
      statement.setAccountNo(stripLabels(header.getAccountNo(), "账号", "卡号", "账号/户名", "账号／户名"));
      statement.setAccountHolder(stripLabels(header.getAccountHolder(), "户名", "户 名", "账号/户名", "账号／户名"));
      statement.setStartDate(header.getStartDate());
      statement.setEndDate(header.getEndDate());
      statement.setCurrency(stripLabels(header.getCurrency(), "币种"));
    }

    StatementSummary summary = dto.getSummary();
    if (summary != null) {
      statement.setTotalExpense(summary.getTotalExpense());
      statement.setExpenseCount(summary.getExpenseCount());
      statement.setTotalIncome(summary.getTotalIncome());
      statement.setIncomeCount(summary.getIncomeCount());
      statement.setApplyTime(summary.getApplyTime());
    }

    List<TransactionDTO> transactions = dto.getTransactions();
    if (transactions != null) {
      for (TransactionDTO tx : transactions) {
        statement.addTransaction(toEntity(tx));
      }
    }
    statement.setTransactionCount(statement.getTransactions().size());
    statement.setRawOcrText(dto.getRawOcrText());
    statement.setSourceFileName(sourceFileName);
    statement.setSourceFileKey(sourceFileKey);
    statement.setOrgId(orgId);
    statement.setOrgName(orgName);

    return bankStatementRepository.save(statement);
  }

  @Transactional(readOnly = true)
  public boolean existsSameStatement(String bankCode, StatementHeader header) {
    return findExisting(bankCode, header).isPresent();
  }

  /**
   * 同银行 + 同账号 + 同起止日期的已有流水单（可能是同一份流水的另一页）。
   */
  @Transactional(readOnly = true)
  public Optional<BankStatement> findSameStatement(String bankCode, StatementHeader header) {
    return findExisting(bankCode, header);
  }

  /**
   * 是否存在同账号且账期重叠的流水单（用于提示"已合并"）
   */
  @Transactional(readOnly = true)
  public boolean hasOverlappingStatement(String bankCode, StatementHeader header) {
    return findOverlapping(bankCode, header).isPresent();
  }

  private Optional<BankStatement> findExisting(String bankCode, StatementHeader header) {
    if (header == null || header.getAccountNo() == null
      || header.getStartDate() == null || header.getEndDate() == null) {
      return Optional.empty();
    }
    String acctNo = stripLabels(header.getAccountNo(), "账号", "卡号", "账号/户名", "账号／户名");
    return bankStatementRepository.findByBankCodeAndAccountNoAndStartDateAndEndDate(
      bankCode, acctNo, header.getStartDate(), header.getEndDate());
  }

  /**
   * 找出同银行同账号、且账期与本次解析结果有重叠的已有流水单。
   */
  private Optional<BankStatement> findOverlapping(String bankCode, StatementHeader header) {
    if (header == null || header.getAccountNo() == null
      || header.getStartDate() == null || header.getEndDate() == null) {
      return Optional.empty();
    }
    String acctNo = stripLabels(header.getAccountNo(), "账号", "卡号", "账号/户名", "账号／户名").trim();
    // 账号比对做去空格容错，避免两份文件的卡号解析格式差异导致匹配不上
    return bankStatementRepository.findByBankCode(bankCode).stream()
      .filter(s -> s.getAccountNo() != null && s.getAccountNo().trim().equals(acctNo))
      .filter(s -> s.getStartDate() != null && s.getEndDate() != null)
      .filter(s -> !s.getStartDate().isAfter(header.getEndDate())
        && !s.getEndDate().isBefore(header.getStartDate()))
      .min(Comparator.comparing(BankStatement::getId));
  }

  /**
   * 把新解析的明细并入已有流水单：按交易指纹去重，账期取并集，汇总按明细重算。
   */
  private BankStatement mergeInto(BankStatement target, BankStatementDTO dto) {
    Set<String> seen = new HashSet<>();
    for (BankStatementTransaction tx : target.getTransactions()) {
      seen.add(fingerprint(tx.getTransDate(), tx.getTransTime(), tx.getAmount(), tx.getBalance(), tx.getCounterpartyAccount()));
    }

    List<TransactionDTO> incoming = dto.getTransactions();
    if (incoming != null) {
      for (TransactionDTO tx : incoming) {
        String fp = fingerprint(tx.getTransDate(), tx.getTransTime(), tx.getAmount(), tx.getBalance(), tx.getCounterpartyAccount());
        if (seen.add(fp)) {
          target.addTransaction(toEntity(tx));
        }
      }
    }

    StatementHeader header = dto.getHeader();
    if (header != null && header.getStartDate() != null && header.getEndDate() != null) {
      if (target.getStartDate() == null || header.getStartDate().isBefore(target.getStartDate())) {
        target.setStartDate(header.getStartDate());
      }
      if (target.getEndDate() == null || header.getEndDate().isAfter(target.getEndDate())) {
        target.setEndDate(header.getEndDate());
      }
    }

    target.setTransactionCount(target.getTransactions().size());
    // 每页解析时序号都从 1 开始，合并后必须按交易日期/时间重排，否则序号重复
    renumberByDateTime(target);
    recalcTotals(target);
    return bankStatementRepository.save(target);
  }

  /**
   * 按交易日期、交易时间、id 重排序号（1..N）。
   * 合并多页后各页序号都从 1 开始，依赖本方法保证序号唯一且与日期顺序一致。
   */
  private void renumberByDateTime(BankStatement statement) {
    List<BankStatementTransaction> list = new ArrayList<>(statement.getTransactions());
    list.sort(BankStatementService::compareTransaction);
    for (int i = 0; i < list.size(); i++) {
      list.get(i).setSeqNo(i + 1);
    }
  }

  private static int compareTransaction(BankStatementTransaction a, BankStatementTransaction b) {
    int c = compareNullable(a.getTransDate(), b.getTransDate());
    if (c != 0) return c;
    c = compareNullable(a.getTransTime(), b.getTransTime());
    if (c != 0) return c;
    c = compareNullable(a.getId(), b.getId());
    if (c != 0) return c;
    return compareNullable(a.getSeqNo(), b.getSeqNo());
  }

  private static <T extends Comparable<T>> int compareNullable(T a, T b) {
    if (a == null && b == null) return 0;
    if (a == null) return 1;
    if (b == null) return -1;
    return a.compareTo(b);
  }

  /**
   * 修复历史数据：只处理序号有重复/缺失的流水单（典型是分页合并后每页从 1 编号）。
   *
   * @return 被修复的流水单数量
   */
  @Transactional
  public int renumberDuplicateSeqNo() {
    int repaired = 0;
    for (BankStatement statement : bankStatementRepository.findAll()) {
      List<BankStatementTransaction> list = new ArrayList<>(statement.getTransactions());
      if (list.size() < 2) {
        continue;
      }
      Set<Integer> seen = new HashSet<>();
      boolean duplicate = false;
      for (BankStatementTransaction tx : list) {
        if (tx.getSeqNo() == null || !seen.add(tx.getSeqNo())) {
          duplicate = true;
          break;
        }
      }
      if (!duplicate) {
        continue;
      }
      list.sort(BankStatementService::compareTransaction);
      for (int i = 0; i < list.size(); i++) {
        list.get(i).setSeqNo(i + 1);
      }
      bankStatementRepository.save(statement);
      repaired++;
    }
    return repaired;
  }

  /**
   * 交易指纹：日期 + 时间 + 金额 + 余额 + 对方账号。
   * 跨期文件重叠部分的同一笔交易，这五项完全一致。
   */
  private String fingerprint(java.time.LocalDate transDate, String transTime,
                             BigDecimal amount, BigDecimal balance, String counterpartyAccount) {
    return (transDate == null ? "" : transDate.toString())
      + "|" + (transTime == null ? "" : transTime)
      + "|" + (amount == null ? "" : amount.stripTrailingZeros().toPlainString())
      + "|" + (balance == null ? "" : balance.stripTrailingZeros().toPlainString())
      + "|" + (counterpartyAccount == null ? "" : counterpartyAccount.trim());
  }

  /**
   * 按明细重算收支合计与笔数（合并后原表头汇总值不再准确）。
   */
  private void recalcTotals(BankStatement statement) {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expense = BigDecimal.ZERO;
    int incomeCount = 0;
    int expenseCount = 0;
    for (BankStatementTransaction tx : statement.getTransactions()) {
      BigDecimal amount = tx.getAmount();
      if (amount == null) {
        continue;
      }
      // 微信「其他」类不计收支
      if ("其他".equals(tx.getDirection())) {
        continue;
      }
      if (amount.signum() >= 0) {
        income = income.add(amount);
        incomeCount++;
      } else {
        expense = expense.add(amount.abs());
        expenseCount++;
      }
    }
    statement.setTotalIncome(income);
    statement.setIncomeCount(incomeCount);
    statement.setTotalExpense(expense);
    statement.setExpenseCount(expenseCount);
  }

  @Transactional(readOnly = true)
  public List<BankStatement> findAll() {
    return bankStatementRepository.findAll();
  }

  @Transactional(readOnly = true)
  public BankStatement findById(Long id) {
    return bankStatementRepository.findById(id).orElse(null);
  }

  /**
   * 兜底清洗：把 parser 可能泄漏的前缀标签（"户名："、"账号/" 等）剥掉。
   *
   * <p>遍历 labels，找到能让 value {@code startsWith(label)} 的那个，截掉 label
   * 以及紧随其后的冒号/斜杠/空白，返回纯值；没命中则原样返回。
   */
  private static String stripLabels(String value, String... labels) {
    if (value == null) return null;
    String v = value.trim();
    for (String label : labels) {
      if (v.startsWith(label)) {
        v = v.substring(label.length()).replaceAll("^[：:／/]+\\s*", "").trim();
        break;
      }
    }
    return v;
  }

  private BankStatementTransaction toEntity(TransactionDTO tx) {
    BankStatementTransaction entity = new BankStatementTransaction();
    entity.setSeqNo(tx.getSeqNo());
    entity.setTransDate(tx.getTransDate());
    entity.setTransTime(tx.getTransTime());
    entity.setTransDateTime(tx.getTransDateTime());
    entity.setAmount(tx.getAmount());
    entity.setBalance(tx.getBalance());
    entity.setDirection(tx.getDirection());
    entity.setRawDirection(tx.getRawDirection());
    entity.setOcrDirection(tx.getOcrDirection());
    entity.setCounterpartyAccount(tx.getCounterpartyAccount());
    entity.setCounterpartyName(tx.getCounterpartyName());
    entity.setChannel(tx.getChannel());
    entity.setPurpose(tx.getPurpose());
    entity.setSummary(tx.getSummary());
    entity.setPostscript(tx.getPostscript());
    entity.setRemark(tx.getRemark());
    entity.setAmountSignExplicit(tx.getAmountSignExplicit());
    return entity;
  }
}
