package tech.wetech.admin3.ocrApi.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tech.wetech.admin3.ocrApi.model.CreditReportItem;

@Repository
public interface CreditReportItemRepository extends JpaRepository<CreditReportItem, Long> {

  @Query("select count(i) from CreditReportItem i where i.creditReport.id = :reportId")
  long countByReportId(Long reportId);

  @Query("select count(i) from CreditReportItem i where i.creditReport.id = :reportId and i.itemType = :itemType")
  long countByReportIdAndType(Long reportId, String itemType);
}
