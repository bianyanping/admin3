package tech.wetech.admin3.ocrApi;

import org.json.JSONArray;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.ocrApi.layout.config.GxrcbLayout;
import tech.wetech.admin3.ocrApi.strategy.ConfigurableStatementParser;
import tech.wetech.admin3.ocrApi.strategy.GxrcbTableStatementParser;
import tech.wetech.admin3.ocrApi.strategy.PsbcTableStatementParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;


public final class OcrSampleLoader {

  private OcrSampleLoader() {}

  /** 从 classpath 读：src/main/resources/{bankCode}/{fileName} */
  public static JSONArray loadFromClasspath(String bankCode, String fileName) {
    String path = bankCode + "/" + fileName;
    try (InputStream in = OcrSampleLoader.class.getClassLoader()
      .getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("找不到样例文件: " + path);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      return new JSONArray(content);
    } catch (IOException e) {
      throw new RuntimeException("读取样例失败: " + path, e);
    }
  }


  /** 列出某银行目录下所有样例文件名（调试时批量跑） */
  public static List<String> listSamples(String bankCode) {
    String dir = "src/main/resources/" + bankCode;
    try (Stream<Path> stream = Files.list(Path.of(dir))) {
      List<String> names = new ArrayList<>();
      stream.filter(p -> p.toString().endsWith(".json"))
        .forEach(p -> names.add(p.getFileName().toString()));
      names.sort(String::compareTo);
      return names;
    } catch (IOException e) {
      throw new RuntimeException("列出样例失败: " + dir, e);
    }
  }

  public static void main(String[] args) {
    // 调试阶段：从文件读，不调 OCR
    tech.wetech.admin3.ocrApi.registry.ParserRegistry registry = new tech.wetech.admin3.ocrApi.registry.ParserRegistry();
    registry.register(new ConfigurableStatementParser(GxrcbLayout.build()));
    registry.register(new PsbcTableStatementParser());
    registry.register(new GxrcbTableStatementParser());
    BankStatementParserFacade facade = new BankStatementParserFacade(registry);

//    print("中国邮政储蓄银行",
//      facade.parse(loadFromClasspath("YZCX", "youzhengchuxu.json"), null, "PSBC"));
    print("广西农商联合银行",
      facade.parse(loadFromClasspath("GXRCB", "20261015134455.json")));
  }

  private static void print(String title, BankStatementDTO dto) {
    System.out.println("===== " + title + " =====");
    System.out.println("表头: " + dto.getHeader());
    System.out.println("汇总: " + dto.getSummary());
    System.out.println("交易笔数: " + dto.getTransactions().size());
    dto.getTransactions().forEach(System.out::println);
  }

}
