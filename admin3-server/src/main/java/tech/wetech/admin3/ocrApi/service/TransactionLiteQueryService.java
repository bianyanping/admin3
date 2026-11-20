package tech.wetech.admin3.ocrApi.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 流水明细的轻量只读查询。
 *
 * <p>分析类接口（首页看板、账户概览、往来对手、关键词分析）只需要少量字段，
 * 用 JdbcTemplate 直接读取行数据，避免为几万行数据构建 JPA 实体与持久化上下文。
 * 查询条件、字段值与实体映射保持一致，不改变任何统计口径。
 */
@Service
public class TransactionLiteQueryService {

  private static final String SELECT = "select id, statement_id, seq_no, trans_date, trans_time, amount, balance, "
    + "direction, counterparty_account, counterparty_name, summary, purpose, postscript, remark "
    + "from bank_statement_transaction ";

  private static final RowMapper<TxnLite> MAPPER = (rs, rowNum) -> new TxnLite(
    rs.getLong("id"),
    rs.getLong("statement_id"),
    rs.getObject("seq_no", Integer.class),
    rs.getObject("trans_date", LocalDate.class),
    rs.getString("trans_time"),
    rs.getBigDecimal("amount"),
    rs.getBigDecimal("balance"),
    rs.getString("direction"),
    rs.getString("counterparty_account"),
    rs.getString("counterparty_name"),
    rs.getString("summary"),
    rs.getString("purpose"),
    rs.getString("postscript"),
    rs.getString("remark"));

  private final JdbcTemplate jdbcTemplate;

  public TransactionLiteQueryService(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 指定流水单下的全部明细（按 id 升序，与原查询的自然顺序一致）。
   */
  public List<TxnLite> findByStatementIds(List<Long> statementIds) {
    if (statementIds == null || statementIds.isEmpty()) {
      return List.of();
    }
    return jdbcTemplate.query(SELECT + "where statement_id in (" + placeholders(statementIds.size()) + ") order by id",
      MAPPER, statementIds.toArray());
  }

  /**
   * 指定流水单、指定交易日期区间内的明细。
   */
  public List<TxnLite> findByStatementIdsAndDateRange(List<Long> statementIds, LocalDate from, LocalDate to) {
    if (statementIds == null || statementIds.isEmpty()) {
      return List.of();
    }
    Object[] args = new Object[statementIds.size() + 2];
    for (int i = 0; i < statementIds.size(); i++) {
      args[i] = statementIds.get(i);
    }
    args[statementIds.size()] = from;
    args[statementIds.size() + 1] = to;
    return jdbcTemplate.query(SELECT + "where statement_id in (" + placeholders(statementIds.size())
      + ") and trans_date between ? and ? order by id", MAPPER, args);
  }

  private static String placeholders(int size) {
    return java.util.stream.IntStream.range(0, size).mapToObj(i -> "?")
      .collect(Collectors.joining(","));
  }

  /**
   * 分析场景使用的明细投影，仅包含统计所需的列。
   */
  public record TxnLite(Long id,
                        Long statementId,
                        Integer seqNo,
                        LocalDate transDate,
                        String transTime,
                        BigDecimal amount,
                        BigDecimal balance,
                        String direction,
                        String counterpartyAccount,
                        String counterpartyName,
                        String summary,
                        String purpose,
                        String postscript,
                        String remark) {
  }
}
