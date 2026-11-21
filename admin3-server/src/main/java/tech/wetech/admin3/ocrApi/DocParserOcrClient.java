package tech.wetech.admin3.ocrApi;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tech.wetech.admin3.common.BusinessException;
import tech.wetech.admin3.common.CommonResultStatus;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 百度智能云「文档解析（Unlimited-OCR）」客户端。
 *
 * <p>接口为异步模式：
 * <ol>
 *   <li>POST {@code /rest/2.0/brain/online/v2/parser/task} 上传 Base64 文件，拿到 task_id；</li>
 *   <li>每 5 秒 POST {@code /parser/task/query} 轮询状态，最长等待 300 秒；</li>
 *   <li>成功后下载 {@code parse_result_url} 指向的解析结果 JSON。</li>
 * </ol>
 *
 * <p>文档要求：PDF ≤ 50MB（file_data 方式）、≤ 2000 页；超过 50MB 需改用 file_url。
 */
public final class DocParserOcrClient {

  private static final Logger log = LoggerFactory.getLogger(DocParserOcrClient.class);

  private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
  private static final String SUBMIT_URL = "https://aip.baidubce.com/rest/2.0/brain/online/v2/unlimited-ocr-parser/task";
  private static final String QUERY_URL = "https://aip.baidubce.com/rest/2.0/brain/online/v2/unlimited-ocr-parser/task/query";

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(5);
  private static final Duration MAX_WAIT = Duration.ofSeconds(300);

  private static final HttpClient CLIENT = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();

  /** access_token 有效期 30 天，这里提前 1 分钟过期 */
  private static volatile String cachedToken;
  private static volatile long tokenExpireAt;

  private DocParserOcrClient() {
  }

  /**
   * 解析整份文档，返回 parse_result_url 下载到的 JSON。
   */
  public static JSONObject parse(byte[] fileBytes, String fileName) {
    String token = accessToken();
    String taskId = submit(token, fileBytes, fileName);
    log.info("文档解析任务已提交，taskId={}，文件={}", taskId, fileName);
    return downloadResult(waitForResult(token, taskId));
  }

  // ==================== access_token ====================

  private static String accessToken() {
    long now = System.currentTimeMillis();
    String token = cachedToken;
    if (token != null && tokenExpireAt > now) {
      return token;
    }
    synchronized (DocParserOcrClient.class) {
      if (cachedToken != null && tokenExpireAt > System.currentTimeMillis()) {
        return cachedToken;
      }
      String body = form(Map.of(
        "grant_type", "client_credentials",
        "client_id", BaiduOcr.API_KEY,
        "client_secret", BaiduOcr.SECRET_KEY
      ));
      JSONObject json = postForm(TOKEN_URL, body, Duration.ofSeconds(30));
      String value = json.optString("access_token", "");
      if (value.isEmpty()) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "获取百度 access_token 失败：" + json.optString("error_description", json.toString()));
      }
      long expiresIn = json.optLong("expires_in", 2592000L);
      cachedToken = value;
      tokenExpireAt = System.currentTimeMillis() + Math.max(expiresIn - 60, 60) * 1000L;
      return value;
    }
  }

  // ==================== 提交 / 轮询 / 下载 ====================

  private static String submit(String token, byte[] fileBytes, String fileName) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("file_data", Base64.getEncoder().encodeToString(fileBytes));
    params.put("file_name", fileName == null || fileName.isBlank() ? "statement.pdf" : fileName);
    // 表格附带 markdown，不额外转 HTML；图片位置信息不需要
    params.put("html_table_format", "false");
    params.put("parse_image_layout", "false");

    JSONObject json = postForm(SUBMIT_URL + "?access_token=" + token,
      form(params), Duration.ofMinutes(3));
    checkError(json, "文档解析任务提交");
    JSONObject result = json.optJSONObject("result");
    String taskId = result == null ? "" : result.optString("task_id", "");
    if (taskId.isEmpty()) {
      throw new BusinessException(CommonResultStatus.FAIL, "文档解析任务提交失败：未返回 task_id");
    }
    return taskId;
  }

  private static JSONObject waitForResult(String token, String taskId) {
    long deadline = System.currentTimeMillis() + MAX_WAIT.toMillis();
    while (System.currentTimeMillis() < deadline) {
      sleep(POLL_INTERVAL);
      JSONObject json = postForm(QUERY_URL + "?access_token=" + token,
        form(Map.of("task_id", taskId)), Duration.ofSeconds(30));
      checkError(json, "文档解析结果查询");
      JSONObject result = json.optJSONObject("result");
      if (result == null) {
        continue;
      }
      String status = result.optString("status", "");
      if ("success".equals(status)) {
        log.info("文档解析完成，taskId={}", taskId);
        return result;
      }
      if ("failed".equals(status)) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "文档解析失败：" + result.optString("task_error", "未知原因"));
      }
    }
    throw new BusinessException(CommonResultStatus.FAIL, "文档解析超时（超过 5 分钟），请稍后重试");
  }

  private static JSONObject downloadResult(JSONObject result) {
    String url = result.optString("parse_result_url", "");
    if (url.isEmpty()) {
      throw new BusinessException(CommonResultStatus.FAIL, "文档解析结果缺少 parse_result_url");
    }
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(Duration.ofMinutes(2))
        .GET()
        .build();
      HttpResponse<String> response = CLIENT.send(request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 400) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "下载文档解析结果失败（HTTP " + response.statusCode() + "）");
      }
      return new JSONObject(response.body());
    } catch (IOException e) {
      throw new BusinessException(CommonResultStatus.FAIL, "下载文档解析结果失败：" + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(CommonResultStatus.FAIL, "下载文档解析结果被中断");
    }
  }

  // ==================== 工具 ====================

  private static JSONObject postForm(String url, String body, Duration timeout) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .timeout(timeout)
        .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build();
      HttpResponse<String> response = CLIENT.send(request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 400) {
        throw new BusinessException(CommonResultStatus.FAIL,
          "文档解析接口请求失败（HTTP " + response.statusCode() + "）");
      }
      return new JSONObject(response.body());
    } catch (IOException e) {
      throw new BusinessException(CommonResultStatus.FAIL, "调用文档解析接口失败：" + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(CommonResultStatus.FAIL, "调用文档解析接口被中断");
    }
  }

  private static void checkError(JSONObject json, String action) {
    int code = json.optInt("error_code", 0);
    if (code != 0) {
      throw new BusinessException(CommonResultStatus.FAIL,
        action + "失败：" + json.optString("error_msg", "错误码 " + code) + "（" + code + "）");
    }
  }

  private static String form(Map<String, String> params) {
    return params.entrySet().stream()
      .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
      .collect(Collectors.joining("&"));
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(CommonResultStatus.FAIL, "文档解析轮询被中断");
    }
  }
}
