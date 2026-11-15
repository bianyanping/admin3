package tech.wetech.admin3.ocrApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.ocrApi.model.CreditReport;

import java.util.Optional;

@Repository
public interface CreditReportRepository extends JpaRepository<CreditReport, Long> {

  /**
   * 按报告编号查找（判重用）
   */
  Optional<CreditReport> findByReportNo(String reportNo);

  /**
   * 按身份证号查找最近一份
   */
  Optional<CreditReport> findTopByIdNoOrderByReportTimeDesc(String idNo);

  @Query("select count(r) from CreditReport r")
  long countReports();
}
