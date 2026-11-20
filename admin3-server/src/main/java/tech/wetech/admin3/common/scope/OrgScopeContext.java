package tech.wetech.admin3.common.scope;

/**
 * 请求级组织上下文：由鉴权拦截器在登录校验通过后写入，请求结束时清理。
 *
 * <p>供 {@link OrgOwnedEntity} 自动归属与 {@link OrgScopeService} 计算可见范围使用，
 * 避免各业务模块自行从 Session 中解析用户与组织。
 */
public final class OrgScopeContext {

  private static final ThreadLocal<OrgScopeContext> HOLDER = new ThreadLocal<>();

  private final Long userId;
  private final Long orgId;
  private final Boolean admin;

  private OrgScopeContext(Long userId, Long orgId, Boolean admin) {
    this.userId = userId;
    this.orgId = orgId;
    this.admin = admin;
  }

  public static void set(Long userId, Long orgId, Boolean admin) {
    HOLDER.set(new OrgScopeContext(userId, orgId, admin));
  }

  public static void clear() {
    HOLDER.remove();
  }

  public static OrgScopeContext current() {
    return HOLDER.get();
  }

  public static Long userId() {
    OrgScopeContext context = HOLDER.get();
    return context == null ? null : context.userId;
  }

  public static Long orgId() {
    OrgScopeContext context = HOLDER.get();
    return context == null ? null : context.orgId;
  }

  public static Boolean admin() {
    OrgScopeContext context = HOLDER.get();
    return context == null ? null : context.admin;
  }
}
