package tech.wetech.admin3.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.wetech.admin3.sys.model.User;
import tech.wetech.admin3.sys.service.SessionService;
import tech.wetech.admin3.sys.service.UserService;
import tech.wetech.admin3.sys.service.dto.UserinfoDTO;

import java.time.LocalDateTime;

/**
 * @author cjbi
 */
@RestController
public class LoginController {

  private final SessionService sessionService;
  private final UserService userService;

  public LoginController(SessionService sessionService, UserService userService) {
    this.sessionService = sessionService;
    this.userService = userService;
  }

  @PostMapping("/login")
  private ResponseEntity<UserinfoDTO> login(@RequestBody @Valid LoginRequest request) {
    return ResponseEntity.ok(sessionService.login(request.username(), request.password()));
  }

  /**
   * 用户自助注册：归属「测试账号」组织并授予「普通用户」角色，账号有效期为 1 天
   */
  @PostMapping("/register")
  public ResponseEntity<RegisterResult> register(@RequestBody @Valid RegisterRequest request) {
    User user = userService.register(request.username(), request.password());
    return ResponseEntity.ok(new RegisterResult(user.getUsername(), user.getExpireTime(), UserService.REGISTER_VALID_DAYS));
  }

  @SecurityRequirement(name = "bearerAuth")
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    String token = request.getHeader("Authorization").replace("Bearer", "").trim();
    sessionService.logout(token);
    return ResponseEntity.ok().build();
  }

  @SecurityRequirement(name = "bearerAuth")
  @GetMapping("/userinfo")
  public ResponseEntity<UserinfoDTO> userInfo(HttpServletRequest request) {
    String token = request.getHeader("Authorization").replace("Bearer", "").trim();
    return ResponseEntity.ok(sessionService.getLoginUserInfo(token));
  }

  record LoginRequest(@NotBlank String username, @NotBlank String password) {
  }

  record RegisterRequest(@NotBlank String username, @NotBlank String password) {
  }

  public record RegisterResult(String username, LocalDateTime expireTime, int validDays) {
  }

}
