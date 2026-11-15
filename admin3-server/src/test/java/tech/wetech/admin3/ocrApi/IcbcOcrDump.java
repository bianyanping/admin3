package tech.wetech.admin3.ocrApi;

import com.baidu.aip.ocr.AipOcr;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

/**
 * 临时调试工具：把工行扫描件 PDF 按页渲染为图片，调百度 OCR 表格接口，
 * 结果写入 target/icbc_ocr/pageN.json，用于分析版式。
 */
public class IcbcOcrDump {

  public static void main(String[] args) throws Exception {
    File pdf = new File("src/main/resources/ICBC/黄总个人工行半年流水记录表.pdf");
    AipOcr client = new AipOcr(BaiduOcr.APP_ID, BaiduOcr.API_KEY, BaiduOcr.SECRET_KEY);
    client.setConnectionTimeoutInMillis(10_000);
    client.setSocketTimeoutInMillis(120_000);

    Path outDir = Path.of("target/icbc_ocr");
    Files.createDirectories(outDir);

    int maxPages = args.length > 0 ? Integer.parseInt(args[0]) : 999;
    try (PDDocument doc = PDDocument.load(pdf)) {
      PDFRenderer renderer = new PDFRenderer(doc);
      int pages = Math.min(maxPages, doc.getNumberOfPages());
      for (int i = 0; i < pages; i++) {
        BufferedImage img = renderer.renderImageWithDPI(i, 200, ImageType.RGB);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        HashMap<String, Object> options = new HashMap<>();
        JSONObject res = client.table(baos.toByteArray(), options);

        // 表格接口会裁掉表格上方的"卡号/户名"行，第 1 页额外跑一次通用 OCR 补全
        if (i == 0) {
          JSONObject general = client.basicAccurateGeneral(baos.toByteArray(), new HashMap<>());
          JSONArray lines = new JSONArray();
          JSONArray words = general.optJSONArray("words_result");
          if (words != null) {
            for (int k = 0; k < words.length(); k++) {
              lines.put(words.getJSONObject(k).optString("words"));
            }
          }
          res.put("general_words_result", lines);
        }

        Files.writeString(outDir.resolve("page" + (i + 1) + ".json"),
          res.toString(2), StandardCharsets.UTF_8);
        System.out.println("page " + (i + 1) + " done, length=" + res.toString().length());
      }
    }
  }
}
