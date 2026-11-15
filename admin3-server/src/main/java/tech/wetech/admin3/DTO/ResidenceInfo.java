package tech.wetech.admin3.DTO;

import lombok.Data;

@Data
public class ResidenceInfo {
  private String address;
  private String status;          // 居住状况
  private String updateDate;
}
