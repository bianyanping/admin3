package tech.wetech.admin3.risk.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.common.authz.Logical;
import tech.wetech.admin3.common.authz.PermissionHelper;
import tech.wetech.admin3.common.authz.RequiresPermissions;
import tech.wetech.admin3.risk.model.RiskExclusionRule;
import tech.wetech.admin3.risk.model.RiskTag;
import tech.wetech.admin3.risk.service.RiskDatabaseService;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.util.List;
import java.util.Map;

/**
 * 风险数据库：资方、黑名单客户、交易对手主数据与标签接口。
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/risk")
public class RiskDatabaseController {

  /** 资方数据库 */
  private static final String PERM_FUNDER_VIEW = "risk:funder:view";
  private static final String PERM_FUNDER_MANAGE = "risk:funder:manage";
  /** 黑名单数据库 */
  private static final String PERM_BLACKLIST_VIEW = "risk:blacklist:view";
  private static final String PERM_BLACKLIST_MANAGE = "risk:blacklist:manage";
  /** 交易对手数据库 */
  private static final String PERM_COUNTERPARTY_VIEW = "risk:counterparty:view";
  private static final String PERM_COUNTERPARTY_MANAGE = "risk:counterparty:manage";

  private final RiskDatabaseService riskDatabaseService;

  public RiskDatabaseController(RiskDatabaseService riskDatabaseService) {
    this.riskDatabaseService = riskDatabaseService;
  }

  private UserinfoDTO currentUser() {
    return (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
  }

  private Long currentUserId() {
    UserinfoDTO currentUser = currentUser();
    return currentUser == null ? null : currentUser.userId();
  }

  private String currentUsername() {
    UserinfoDTO currentUser = currentUser();
    return currentUser == null ? null : currentUser.username();
  }

  /**
   * 同一接口被多个模块共用时，按实际参数做模块级权限校验。
   */
  private void requireAnyPermission(String... permissions) {
    UserinfoDTO currentUser = currentUser();
    if (currentUser == null
      || !PermissionHelper.isPermitted(currentUser.permissions(), permissions, Logical.OR)) {
      throw new BusinessException(CommonResultStatus.FORBIDDEN);
    }
  }

  /**
   * 标签维护权限：客户黑名单归黑名单库，对手资方归资方库，其余对手标签归交易对手库。
   */
  private void requireTagPermission(String subjectType, String tagCode) {
    if (RiskTag.SUBJECT_CUSTOMER.equals(subjectType)) {
      requireAnyPermission(PERM_BLACKLIST_MANAGE);
      return;
    }
    if ("FUNDER".equalsIgnoreCase(tagCode)) {
      requireAnyPermission(PERM_FUNDER_MANAGE);
      return;
    }
    requireAnyPermission(PERM_COUNTERPARTY_MANAGE);
  }

  /**
   * 风险数据库汇总：沉淀对手数、消费型剔除数、农户/客户数、黑名单与资方数量、剔除规则。
   */
  @GetMapping("/summary")
  @RequiresPermissions(PERM_COUNTERPARTY_VIEW)
  public ResponseEntity<RiskDatabaseService.Summary> summary() {
    return ResponseEntity.ok(riskDatabaseService.summary(currentUserId()));
  }

  /**
   * 强制重建对手主数据（剔除规则变更后也可由前端触发）。
   */
  @PostMapping("/sync")
  @RequiresPermissions(PERM_COUNTERPARTY_MANAGE)
  public ResponseEntity<RiskDatabaseService.SyncResult> sync() {
    return ResponseEntity.ok(riskDatabaseService.syncIfNeeded(currentUserId(), true));
  }

  /**
   * 交易对手分页列表，支持名称/卡号搜索、消费型过滤、标签过滤与排序。
   *
   * @param consumer EXCLUDE=只看保留（默认），INCLUDE=全部，ONLY=只看消费型
   */
  @GetMapping("/counterparties")
  @RequiresPermissions(value = {PERM_FUNDER_VIEW, PERM_COUNTERPARTY_VIEW}, logical = Logical.OR)
  public ResponseEntity<Page<RiskDatabaseService.CounterpartyView>> counterparties(
    @RequestParam(value = "keyword", required = false) String keyword,
    @RequestParam(value = "consumer", defaultValue = "EXCLUDE") String consumer,
    @RequestParam(value = "tagCode", required = false) String tagCode,
    @RequestParam(value = "page", defaultValue = "1") int page,
    @RequestParam(value = "size", defaultValue = "20") int size,
    @RequestParam(value = "sortBy", defaultValue = "txnCount") String sortBy,
    @RequestParam(value = "sortDir", defaultValue = "desc") String sortDir) {
    // 资方库复用对手列表接口（tagCode=FUNDER），需按实际模块校验权限
    if ("FUNDER".equalsIgnoreCase(tagCode)) {
      requireAnyPermission(PERM_FUNDER_VIEW);
    } else {
      requireAnyPermission(PERM_COUNTERPARTY_VIEW);
    }
    return ResponseEntity.ok(riskDatabaseService.counterparties(currentUserId(), keyword, consumer,
      tagCode, page, size, sortBy, sortDir));
  }

  /**
   * 客户/户主列表：账户数、流水单数、流水笔数、征信报告数与标签。
   */
  @GetMapping("/customers")
  @RequiresPermissions(PERM_BLACKLIST_VIEW)
  public ResponseEntity<List<RiskDatabaseService.CustomerView>> customers(
    @RequestParam(value = "keyword", required = false) String keyword) {
    return ResponseEntity.ok(riskDatabaseService.customers(currentUserId(), keyword));
  }

  @GetMapping("/tags")
  @RequiresPermissions(value = {PERM_FUNDER_VIEW, PERM_BLACKLIST_VIEW, PERM_COUNTERPARTY_VIEW}, logical = Logical.OR)
  public ResponseEntity<List<RiskDatabaseService.TagView>> tags(
    @RequestParam(value = "subjectType", required = false) String subjectType) {
    return ResponseEntity.ok(riskDatabaseService.listTags(subjectType));
  }

  /**
   * 批量取标签：页面里出现户名的地方一次性取回，避免逐行请求。
   */
  @PostMapping("/tags/batch")
  public ResponseEntity<Map<String, List<RiskDatabaseService.TagView>>> batchTags(
    @RequestBody @Valid BatchTagsRequest request) {
    String subjectType = requireSubjectType(request.subjectType());
    List<String> names = request.names() == null ? List.of() : request.names();
    return ResponseEntity.ok(riskDatabaseService.tagsByNames(subjectType, names, currentUserId()));
  }

  /**
   * 手工打标：一个主体可挂多个标签，重复标记幂等。
   */
  @PostMapping("/tags/link")
  @RequiresPermissions(value = {PERM_FUNDER_MANAGE, PERM_BLACKLIST_MANAGE, PERM_COUNTERPARTY_MANAGE},
    logical = Logical.OR)
  public ResponseEntity<List<RiskDatabaseService.TagView>> linkTag(
    @RequestBody @Valid LinkTagRequest request) {
    String subjectType = requireSubjectType(request.subjectType());
    requireTagPermission(subjectType, request.tagCode());
    try {
      return ResponseEntity.ok(riskDatabaseService.linkTag(subjectType, request.subjectKey(),
        request.tagCode(), currentUserId(), currentUsername()));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  /**
   * 取消标记。
   */
  @DeleteMapping("/tags/link")
  @RequiresPermissions(value = {PERM_FUNDER_MANAGE, PERM_BLACKLIST_MANAGE, PERM_COUNTERPARTY_MANAGE},
    logical = Logical.OR)
  public ResponseEntity<Void> unlinkTag(
    @RequestParam("subjectType") String subjectType,
    @RequestParam("subjectKey") String subjectKey,
    @RequestParam("tagCode") String tagCode) {
    String type = requireSubjectType(subjectType);
    requireTagPermission(type, tagCode);
    try {
      riskDatabaseService.unlinkTag(type, subjectKey, tagCode, currentUserId());
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  // ==================== 消费型剔除规则 ====================

  @GetMapping("/exclusions")
  @RequiresPermissions(PERM_COUNTERPARTY_VIEW)
  public ResponseEntity<List<RiskDatabaseService.RuleView>> listRules() {
    return ResponseEntity.ok(riskDatabaseService.listRules());
  }

  @PostMapping("/exclusions")
  @RequiresPermissions(PERM_COUNTERPARTY_MANAGE)
  public ResponseEntity<RiskDatabaseService.RuleView> createRule(@RequestBody @Valid RuleRequest request) {
    try {
      return new ResponseEntity<>(riskDatabaseService.createRule(toRule(request)), HttpStatus.CREATED);
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  @PutMapping("/exclusions/{id}")
  @RequiresPermissions(PERM_COUNTERPARTY_MANAGE)
  public ResponseEntity<RiskDatabaseService.RuleView> updateRule(@PathVariable Long id,
                                                                 @RequestBody @Valid RuleRequest request) {
    try {
      return ResponseEntity.ok(riskDatabaseService.updateRule(id, toRule(request)));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  @PutMapping("/exclusions/{id}/enabled")
  @RequiresPermissions(PERM_COUNTERPARTY_MANAGE)
  public ResponseEntity<RiskDatabaseService.RuleView> setRuleEnabled(@PathVariable Long id,
                                                                     @RequestParam("enabled") boolean enabled) {
    try {
      return ResponseEntity.ok(riskDatabaseService.setRuleEnabled(id, enabled));
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  @DeleteMapping("/exclusions/{id}")
  @RequiresPermissions(PERM_COUNTERPARTY_MANAGE)
  public ResponseEntity<Void> deleteRule(@PathVariable Long id) {
    try {
      riskDatabaseService.deleteRule(id);
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException e) {
      throw new BusinessException(CommonResultStatus.PARAM_ERROR, e.getMessage());
    }
  }

  private RiskExclusionRule toRule(RuleRequest request) {
    RiskExclusionRule rule = new RiskExclusionRule();
    rule.setKeyword(request.keyword());
    rule.setMatchType(request.matchType());
    rule.setCategory(request.category());
    rule.setEnabled(request.enabled());
    rule.setRemark(request.remark());
    return rule;
  }

  private String requireSubjectType(String subjectType) {
    if (RiskTag.SUBJECT_CUSTOMER.equals(subjectType) || RiskTag.SUBJECT_COUNTERPARTY.equals(subjectType)) {
      return subjectType;
    }
    throw new BusinessException(CommonResultStatus.PARAM_ERROR, "主体类型不合法");
  }

  public record BatchTagsRequest(@NotBlank String subjectType, List<String> names) {
  }

  public record LinkTagRequest(@NotBlank String subjectType, @NotBlank String subjectKey,
                               @NotBlank String tagCode) {
  }

  public record RuleRequest(@NotBlank String keyword, String matchType, String category,
                            Boolean enabled, String remark) {
  }
}
