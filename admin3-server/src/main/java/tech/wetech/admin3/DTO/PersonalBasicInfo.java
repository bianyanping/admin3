package tech.wetech.admin3.DTO;

import lombok.Data;

import java.util.List;

// ========== 个人基本信息 ==========
@Data
public class PersonalBasicInfo {
  private String name;
  private String gender;
  private String maritalStatus;
  private String education;
  private List<ResidenceInfo> residences;   // 居住信息（可多条）
  private List<EmploymentInfo> employments; // 职业信息（可多条）
}
