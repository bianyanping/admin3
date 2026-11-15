package tech.wetech.admin3.DTO;

import lombok.Data;

// ========== 公共信息 ==========
@Data
public class PublicRecordDTO {
  private String recordType;      // 欠税/民事判决/强制执行/行政处罚/公积金
  private String content;
  private String occurDate;
  private String amount;          // 如有
}
