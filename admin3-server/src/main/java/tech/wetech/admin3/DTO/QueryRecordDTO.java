package tech.wetech.admin3.DTO;

import lombok.Data;

// ========== 查询记录 ==========
@Data
public class QueryRecordDTO {
  private String queryDate;
  private String queryReason;     // 贷款审批/信用卡审批/本人查询
  private String queryOrg;
}
