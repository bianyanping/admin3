package tech.wetech.admin3.keyword.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.common.authz.RequiresPermissions;
import tech.wetech.admin3.keyword.model.KeywordGroup;
import tech.wetech.admin3.keyword.service.KeywordAnalysisService;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.util.List;

/**
 * 流水关键词分组维护与命中分析。
 *
 * <p>不单独开菜单，配置与统计都在「流水分析」页面内使用。
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/keyword-groups")
public class KeywordGroupController {

  private final KeywordAnalysisService keywordAnalysisService;

  public KeywordGroupController(KeywordAnalysisService keywordAnalysisService) {
    this.keywordAnalysisService = keywordAnalysisService;
  }

  private Long currentUserId() {
    UserinfoDTO currentUser = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    return currentUser == null ? null : currentUser.userId();
  }

  @GetMapping
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<List<KeywordGroup>> list() {
    return ResponseEntity.ok(keywordAnalysisService.listGroups(currentUserId()));
  }

  @PostMapping
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<KeywordGroup> create(@RequestBody @Valid GroupRequest request) {
    try {
      return new ResponseEntity<>(keywordAnalysisService.createGroup(toEntity(request), currentUserId()),
        HttpStatus.CREATED);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  @PutMapping("/{id}")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<KeywordGroup> update(@PathVariable Long id, @RequestBody @Valid GroupRequest request) {
    try {
      return ResponseEntity.ok(keywordAnalysisService.updateGroup(id, toEntity(request), currentUserId()));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  @PutMapping("/{id}/enabled")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<KeywordGroup> setEnabled(@PathVariable Long id,
                                                 @RequestParam("enabled") boolean enabled) {
    try {
      return ResponseEntity.ok(keywordAnalysisService.setEnabled(id, enabled, currentUserId()));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  @DeleteMapping("/{id}")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<Void> delete(@PathVariable Long id) {
    try {
      keywordAnalysisService.deleteGroup(id, currentUserId());
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  /**
   * 关键词命中分析：按当前可见范围内的流水计算，可按户名收窄。
   */
  @GetMapping("/analysis")
  @RequiresPermissions("bankStatement:analysis")
  public ResponseEntity<KeywordAnalysisService.AnalysisResult> analysis(
    @RequestParam(value = "accountHolder", required = false) String accountHolder) {
    return ResponseEntity.ok(keywordAnalysisService.analyze(currentUserId(), accountHolder));
  }

  private KeywordGroup toEntity(GroupRequest request) {
    KeywordGroup group = new KeywordGroup();
    group.setName(request.name());
    group.setKeywords(request.keywords());
    group.setFields(request.fields());
    group.setDirection(request.direction());
    group.setEnabled(request.enabled());
    group.setRemark(request.remark());
    return group;
  }

  public record GroupRequest(@NotBlank String name, @NotBlank String keywords,
                             String fields, String direction, Boolean enabled, String remark) {
  }
}
