package tech.wetech.admin3.ocrApi;


import com.baidu.aip.ocr.AipOcr;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.json.JSONArray;
import org.json.JSONObject;
import tech.wetech.admin3.ocrApi.util.Base64Util;
import tech.wetech.admin3.ocrApi.util.FileUtil;
import tech.wetech.admin3.ocrApi.util.HttpUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
public class BaiduOcr {
  //设置APPID/AK/SK
  public static final String APP_ID = "8137462";
  public static final String API_KEY = "DSSsCG6H8V551EOdc6YVoKlu";
  public static final String SECRET_KEY = "wLbzjFt0seNJdEkS5FHBfru7V6g7jWzZ";

  public static void main(String[] args) {
    // 初始化一个AipOcr
    AipOcr client = new AipOcr(APP_ID, API_KEY, SECRET_KEY);

    // 可选：设置网络连接参数
    client.setConnectionTimeoutInMillis(2000);
    client.setSocketTimeoutInMillis(60000);

    // 调用接口
    String path = "C:\\Users\\YYKJ\\Desktop\\admin3\\本行账户明细下载20261015135120533466.pdf";
    File pdfFile = new File(path);
    // 2. 读取 PDF 文件为字节数组
    byte[] pdfBytes;
    try (FileInputStream fis = new FileInputStream(pdfFile)) {
      pdfBytes = new byte[(int) pdfFile.length()];
      fis.read(pdfBytes);
    } catch (FileNotFoundException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    // 用 PDFBox 获取总页数
    int totalPages;
    try (PDDocument doc = PDDocument.load(pdfFile)) {
      totalPages = doc.getNumberOfPages();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    JSONArray allPagesResults = new JSONArray();

    // 从第 1 页循环到最后一页
    for (int page = 1; page <= totalPages; page++) {
      HashMap<String, String> options = new HashMap<>();
      options.put("pdf_file_num", String.valueOf(page));

//      JSONObject res = client.basicGeneralPdf(pdfBytes, options);
//      JSONObject res = client.multipleInvoicePdf(pdfBytes, 1, null);
//      allPagesResults.put(res);
      // 2. Base64 编码（标准字母表，含 + / =）
      String base64 = Base64.getEncoder().encodeToString(pdfBytes);

      // 3. URL encode
      String urlEncoded = URLEncoder.encode(base64, StandardCharsets.UTF_8);

      // 4. 可选：把 form 编码的 '+'（空格）还原成 %20，得到严格 percent-encoding
      urlEncoded = urlEncoded.replace("+", "%20");

      String param = "pdf_file=" + urlEncoded + "&pdf_file_num=" + page+"&is_sync=true";

      // 注意这里仅为了简化编码每一次请求都去获取access_token，线上环境access_token有过期时间， 客户端可自行缓存，过期后重新获取。
      String accessToken = "25.dfe8be687dd461fbf2daf2327a6df443.315360000.2109545185.282335-124629582\n";
      // 请求url
      String url = "https://aip.baidubce.com/rest/2.0/ocr/v1/table";
      String result = null;
      try {
        result = HttpUtil.post(url, accessToken, param);
      } catch (Exception e) {
        System.out.println("第 " + page + " 页识别出错");
        throw new RuntimeException(e);
      }
      allPagesResults.put(result);
      System.out.println("第 " + page + " 页识别完成");
    }
    System.out.println(allPagesResults.toString(2));



    String bankCode = "GLGMCZYH";
    String originalFileName = pdfFile.getName();
    String fileName = originalFileName.substring(0, originalFileName.lastIndexOf('.'));
    Path outputDir = Path.of("admin3-server/src/main/resources/" + bankCode);
    try {
      Files.createDirectories(outputDir);
      Path outputFile = outputDir.resolve(fileName + ".json");
      Files.writeString(outputFile, allPagesResults.toString(2), StandardCharsets.UTF_8);
      System.out.println("结果已保存到: " + outputFile.toAbsolutePath());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }


  }





}

