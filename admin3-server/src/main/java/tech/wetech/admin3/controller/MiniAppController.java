package tech.wetech.admin3.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;
import tech.wetech.admin3.infra.service.SessionManager;
import tech.wetech.admin3.sys.model.User;
import tech.wetech.admin3.sys.model.UserCredential;
import tech.wetech.admin3.sys.repository.UserCredentialRepository;
import tech.wetech.admin3.sys.repository.UserRepository;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

/**
 * 微信小程序登录接口。
 *
 * <p>小程序不再按 openid 隔离数据，而是用 openid 绑定后台账号，
 * 登录后签发与 Web 端相同的会话 token，后续查询直接复用
 * {@code /bank-statements} 等接口，可见范围由后台的组织架构与分享关系决定。
 */
@RestController
@RequestMapping("/api/miniapp")
public class MiniAppController {

  private static final Logger log = LoggerFactory.getLogger(MiniAppController.class);
  private static final String JSCODE2SESSION = "https://api.weixin.qq.com/sns/jscode2session";
  private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final UserRepository userRepository;
  private final UserCredentialRepository credentialRepository;
  private final SessionManager sessionManager;
  private final ObjectMapper objectMapper;

  @Value("${wechat.miniapp.appid:}")
  private String appid;

  @Value("${wechat.miniapp.secret:}")
  private String secret;

  public MiniAppController(UserRepository userRepository,
                           UserCredentialRepository credentialRepository,
                           SessionManager sessionManager,
                           ObjectMapper objectMapper) {
    this.userRepository = userRepository;
    this.credentialRepository = credentialRepository;
    this.sessionManager = sessionManager;
    this.objectMapper = objectMapper;
  }

  /**
   * 小程序登录：openid 已绑定后台账号则直接签发会话，否则返回待绑定的 openid。
   */
  @PostMapping("/login")
  public ResponseEntity<LoginResult> login(@RequestBody @Valid LoginRequest request) {
    String openid = resolveOpenid(request.code());
    return userRepository.findByWxOpenid(openid)
      .map(user -> ResponseEntity.ok(issueSession(user, openid)))
      .orElseGet(() -> ResponseEntity.ok(new LoginResult(false, openid, null, null)));
  }

  /**
   * 首次登录绑定后台账号：校验后台账号密码后把 openid 写到该账号上。
   */
  @PostMapping("/bind")
  public ResponseEntity<LoginResult> bind(@RequestBody @Valid BindRequest request) {
    String openid = resolveOpenid(request.code());
    UserCredential credential = credentialRepository
      .findCredential(request.username(), UserCredential.IdentityType.PASSWORD)
      .orElseThrow(() -> new BusinessException(CommonResultStatus.UNAUTHORIZED, "账号或密码不正确"));
    if (!credential.doCredentialMatch(request.password())) {
      throw new BusinessException(CommonResultStatus.UNAUTHORIZED, "账号或密码不正确");
    }
    User user = credential.getUser();
    if (user.isLocked()) {
      throw new BusinessException(CommonResultStatus.UNAUTHORIZED, "用户已经停用，请与管理员联系");
    }
    if (user.getWxOpenid() != null && !user.getWxOpenid().equals(openid)) {
      throw new BusinessException(CommonResultStatus.FAIL, "该账号已绑定其他微信，请先联系管理员解绑");
    }
    user.setWxOpenid(openid);
    userRepository.save(user);
    return ResponseEntity.ok(issueSession(user, openid));
  }

  private LoginResult issueSession(User user, String openid) {
    UserCredential credential = credentialRepository
      .findCredential(user.getUsername(), UserCredential.IdentityType.PASSWORD)
      .orElse(null);
    if (credential == null) {
      throw new BusinessException(CommonResultStatus.FAIL, "该账号缺少登录凭证，请联系管理员重置密码");
    }
    String token = UUID.randomUUID().toString().replace("-", "");
    UserinfoDTO userinfo = new UserinfoDTO(token, user.getId(), user.getUsername(), user.getAvatar(), user.getIntro(),
      new UserinfoDTO.Credential(credential.getIdentifier(), credential.getIdentityType()),
      user.findPermissions());
    sessionManager.store(token, credential, userinfo);

    String orgName = user.getOrganization() == null ? null : user.getOrgFullName();
    MiniUser miniUser = new MiniUser(user.getId(), user.getUsername(), user.getUsername(), user.getAvatar(),
      orgName, user.findPermissions(), TIME_FORMATTER.format(LocalDateTime.now()));
    return new LoginResult(true, openid, token, miniUser);
  }

  /**
   * 用 code 换取 openid。
   */
  private String resolveOpenid(String code) {
    if (appid == null || appid.isBlank() || secret == null || secret.isBlank()) {
      throw new BusinessException(CommonResultStatus.FAIL,
        "服务端未配置小程序 AppID/AppSecret，请设置 wechat.miniapp.secret 后重启");
    }
    String url = UriComponentsBuilder.fromHttpUrl(JSCODE2SESSION)
      .queryParam("appid", appid)
      .queryParam("secret", secret)
      .queryParam("js_code", code)
      .queryParam("grant_type", "authorization_code")
      .build()
      .toUriString();
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
      HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(url))
        .GET()
        .timeout(Duration.ofSeconds(8))
        .build();
      HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
      JsonNode node = objectMapper.readTree(response.body());
      if (node.hasNonNull("openid")) {
        return node.get("openid").asText();
      }
      log.warn("jscode2session failed, body={}", response.body());
      throw new BusinessException(CommonResultStatus.UNAUTHORIZED,
        "微信登录失败：" + node.path("errmsg").asText("未知错误"));
    } catch (IOException e) {
      throw new BusinessException(CommonResultStatus.FAIL, "连接微信服务失败：" + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(CommonResultStatus.FAIL, "微信登录被中断");
    }
  }

  record LoginRequest(@NotBlank String code) {
  }

  record BindRequest(@NotBlank String code, @NotBlank String username, @NotBlank String password) {
  }

  public record LoginResult(boolean bound, String openid, String token, MiniUser user) {
  }

  public record MiniUser(Long userId, String username, String nickname, String avatar,
                         String orgName, Set<String> permissions, String lastLoginTime) {
  }
}
