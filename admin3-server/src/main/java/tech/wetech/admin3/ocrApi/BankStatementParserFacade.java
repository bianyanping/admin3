package tech.wetech.admin3.ocrApi;

import org.json.JSONArray;
import org.json.JSONObject;
import tech.wetech.admin3.DTO.BankStatementDTO;
import tech.wetech.admin3.ocrApi.registry.ParserRegistry;
import tech.wetech.admin3.ocrApi.strategy.BankStatementParserStrategy;
import tech.wetech.admin3.ocrApi.strategy.ParseContext;

import java.util.ArrayList;
import java.util.List;

public class BankStatementParserFacade {

  private final ParserRegistry registry;

  public BankStatementParserFacade(ParserRegistry registry) {
    this.registry = registry;
  }

  public BankStatementDTO parse(JSONArray pages) {
    return parse(pages, null, null);
  }

  public BankStatementDTO parse(JSONArray pages, String fileName, String explicitBankCode) {
    // 兼容外层为 JSON 字符串数组的 OCR 响应
    JSONArray normalized = normalizePages(pages);
    ParseContext context = buildContext(normalized, fileName, explicitBankCode);
    BankStatementParserStrategy strategy = registry.resolve(context);
    if (strategy == null) {
      throw new IllegalArgumentException("无法识别银行版式，fileName=" + fileName);
    }
    return strategy.parse(normalized);
  }

  private JSONArray normalizePages(JSONArray pages) {
    JSONArray normalized = new JSONArray();
    for (int i = 0; i < pages.length(); i++) {
      Object item = pages.get(i);
      if (item instanceof String s) {
        normalized.put(new JSONObject(s));
      } else {
        normalized.put(item);
      }
    }
    return normalized;
  }

  private ParseContext buildContext(JSONArray pages, String fileName, String explicitBankCode) {
    List<String> firstPage = new ArrayList<>();
    List<String> all = new ArrayList<>();
    for (int p = 0; p < pages.length(); p++) {
      JSONObject page = pages.getJSONObject(p);
      // 通用 OCR：words_result
      if (page.has("words_result")) {
        collectWords(page.getJSONArray("words_result"), p, all, firstPage);
      }
      // 表格 OCR：tables_result（如邮储回单）
      if (page.has("tables_result")) {
        JSONArray tables = page.getJSONArray("tables_result");
        for (int t = 0; t < tables.length(); t++) {
          JSONObject table = tables.getJSONObject(t);
          collectWords(table.optJSONArray("header"), p, all, firstPage);
          collectWords(table.optJSONArray("body"), p, all, firstPage);
          collectWords(table.optJSONArray("footer"), p, all, firstPage);
        }
      }
    }
    return new ParseContext(all, firstPage, fileName, explicitBankCode);
  }

  private void collectWords(JSONArray arr, int pageIndex,
                            List<String> all, List<String> firstPage) {
    if (arr == null) {
      return;
    }
    for (int i = 0; i < arr.length(); i++) {
      String w = arr.getJSONObject(i).optString("words", "");
      if (w.isBlank()) {
        continue;
      }
      all.add(w);
      if (pageIndex == 0) {
        firstPage.add(w);
      }
    }
  }
}
