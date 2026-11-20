package tech.wetech.admin3.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tech.wetech.admin3.common.Constants;
import tech.wetech.admin3.common.SessionItemHolder;
import tech.wetech.admin3.common.authz.RequiresPermissions;
import tech.wetech.admin3.lending.model.LendingPlatform;
import tech.wetech.admin3.lending.model.LendingTransactionMatch;
import tech.wetech.admin3.lending.service.LendingService;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.util.List;

/**
 * 网贷（网络小贷/消费金融）识别与统计接口。
 *
 * <p>关键词库可手工维护；匹配与统计均基于已入库的银行流水，不修改流水数据。
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/lending")
public class LendingController {

  private final LendingService lendingService;

  public LendingController(LendingService lendingService) {
    this.lendingService = lendingService;
  }

  private Long currentUserId() {
    UserinfoDTO currentUser = (UserinfoDTO) SessionItemHolder.getItem(Constants.SESSION_CURRENT_USER);
    return currentUser == null ? null : currentUser.userId();
  }

  // ==================== 关键词库 ====================

  /**
   * 网贷平台关键词库分页列表。
   */
  @GetMapping("/platforms")
  @RequiresPermissions("lending:view")
  public ResponseEntity<Page<LendingPlatform>> listPlatforms(
    @RequestParam(value = "keyword", required = false) String keyword,
    @RequestParam(value = "enabled", required = false) Boolean enabled,
    @RequestParam(value = "page", defaultValue = "1") int page,
    @RequestParam(value = "size", defaultValue = "20") int size) {
    return ResponseEntity.ok(lendingService.listPlatforms(currentUserId(), keyword, enabled, page, size));
  }

  /**
   * 平台下拉选项（含停用平台，便于筛选历史命中）。
   */
  @GetMapping("/platforms/options")
  @RequiresPermissions("lending:view")
  public ResponseEntity<List<LendingService.PlatformOption>> platformOptions() {
    return ResponseEntity.ok(lendingService.listPlatformOptions(currentUserId()));
  }

  /**
   * 一键初始化预置平台，可重复执行：已存在的名称跳过。
   */
  @PostMapping("/platforms/init")
  @RequiresPermissions("lending:manage")
  public ResponseEntity<LendingService.InitResult> initPresets() {
    return ResponseEntity.ok(lendingService.initPresets(currentUserId()));
  }

  @PostMapping("/platforms")
  @RequiresPermissions("lending:manage")
  public ResponseEntity<LendingPlatform> createPlatform(@RequestBody @Valid PlatformRequest request) {
    LendingPlatform platform = lendingService.createPlatform(currentUserId(), request.name(), request.keywords(),
      request.enabled(), request.remark());
    return new ResponseEntity<>(platform, HttpStatus.CREATED);
  }

  @PutMapping("/platforms/{id}")
  @RequiresPermissions("lending:manage")
  public ResponseEntity<LendingPlatform> updatePlatform(@PathVariable Long id,
                                                        @RequestBody @Valid PlatformRequest request) {
    return ResponseEntity.ok(lendingService.updatePlatform(currentUserId(), id, request.name(), request.keywords(),
      request.enabled(), request.remark()));
  }

  @DeleteMapping("/platforms/{id}")
  @RequiresPermissions("lending:manage")
  public ResponseEntity<Void> deletePlatform(@PathVariable Long id) {
    lendingService.deletePlatform(currentUserId(), id);
    return ResponseEntity.noContent().build();
  }

  /**
   * 启用/停用平台。
   */
  @PatchMapping("/platforms/{id}/enabled")
  @RequiresPermissions("lending:manage")
  public ResponseEntity<LendingPlatform> setPlatformEnabled(@PathVariable Long id,
                                                            @RequestBody @Valid EnabledRequest request) {
    return ResponseEntity.ok(lendingService.setPlatformEnabled(currentUserId(), id, request.enabled()));
  }

  // ==================== 匹配与统计 ====================

  /**
   * 按当前关键词库重建命中记录（全量重算，可重复执行）。
   */
  @PostMapping("/matches/refresh")
  @RequiresPermissions("lending:manage")
  public ResponseEntity<LendingService.RefreshResult> refreshMatches() {
    return ResponseEntity.ok(lendingService.refreshMatches(currentUserId()));
  }

  /**
   * 按月统计：收到网贷 / 扣款网贷的合计、笔数、涉及平台数及每家金额。
   *
   * @param accountHolder 客户（账户户名）过滤，__UNKNOWN__ 表示未命名户主
   */
  @GetMapping("/monthly-stats")
  @RequiresPermissions("lending:view")
  public ResponseEntity<LendingService.MonthlyStats> monthlyStats(
    @RequestParam(value = "startMonth", required = false) String startMonth,
    @RequestParam(value = "endMonth", required = false) String endMonth,
    @RequestParam(value = "accountHolder", required = false) String accountHolder) {
    return ResponseEntity.ok(lendingService.monthlyStats(currentUserId(), startMonth, endMonth, accountHolder));
  }

  /**
   * 客户（账户户名）选项，来源于当前用户可见的流水单。
   */
  @GetMapping("/customers")
  @RequiresPermissions("lending:view")
  public ResponseEntity<List<LendingService.CustomerOption>> customers() {
    return ResponseEntity.ok(lendingService.listCustomers(currentUserId()));
  }

  /**
   * 网贷命中明细分页，可按月份、平台、方向、客户筛选。
   */
  @GetMapping("/transactions")
  @RequiresPermissions("lending:view")
  public ResponseEntity<Page<LendingTransactionMatch>> transactions(
    @RequestParam(value = "month", required = false) String month,
    @RequestParam(value = "platformId", required = false) Long platformId,
    @RequestParam(value = "direction", required = false) String direction,
    @RequestParam(value = "accountHolder", required = false) String accountHolder,
    @RequestParam(value = "page", defaultValue = "1") int page,
    @RequestParam(value = "size", defaultValue = "20") int size) {
    return ResponseEntity.ok(lendingService.transactions(currentUserId(), month, platformId, direction,
      accountHolder, page, size));
  }

  record PlatformRequest(@NotBlank String name, @NotBlank String keywords, Boolean enabled, String remark) {
  }

  record EnabledRequest(@NotNull Boolean enabled) {
  }
}
