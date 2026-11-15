package tech.wetech.admin3.DTO;

import lombok.Data;

import java.util.List;

// ========== 顶层容器 ==========
@Data
public class CreditReportDTO {
  private ReportHeader header;
  private PersonalBasicInfo basicInfo;
  private CreditSummary summary;
  private List<CreditAccountDTO> creditAccounts;   // 信贷交易明细
  private List<PublicRecordDTO> publicRecords;     // 公共信息
  private List<QueryRecordDTO> queryRecords;       // 查询记录
  private String rawOcrText;                       // 保留原始OCR文本用于追溯
}

