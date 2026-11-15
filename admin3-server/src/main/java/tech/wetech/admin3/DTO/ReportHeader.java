package tech.wetech.admin3.DTO;

import lombok.Data;

// ========== 报告头 ==========
@Data
public class ReportHeader {
  private String reportId;
  private String queryDate;       // 报告生成/查询日期
  private String certType;
  private String certNo;          // 脱敏后展示
}
