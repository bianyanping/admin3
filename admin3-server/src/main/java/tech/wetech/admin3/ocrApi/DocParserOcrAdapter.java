package tech.wetech.admin3.ocrApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「文档解析（Unlimited-OCR）」的结果转换成现有解析器认识的页面 JSON。
 *
 * <p>现有解析器（IcbcOcrStatementParser、PsbcTableStatementParser 等）读取的结构是：
 * <pre>
 * [{
 *   "words_result":     [ {"words": "行文本"} ],            // 通用段落解析
 *   "general_words_result": ["第 1 页文本行"],               // 页眉户名/卡号
 *   "tables_result": [{
 *      "header": [ {"words": "表格上方文本"} ],
 *      "body":   [ {"words": "单元格", "row_start": 0, "col_start": 0} ],
 *      "footer": []
 *   }]
 * }]
 * </pre>
 *
 * <p>Unlimited-OCR 的结构是 {@code pages[].text / layouts[] / tables[].cells[] + matrix[][]}，
 * 这里把 matrix 展开成带 row_start / col_start 的扁平单元格列表；表格缺失时退化为解析 markdown 表格。
 */
public final class DocParserOcrAdapter {

  private DocParserOcrAdapter() {
  }

  public static JSONArray toLegacyPages(JSONObject parsed) {
    JSONArray pages = new JSONArray();
    JSONArray source = parsed == null ? null : parsed.optJSONArray("pages");
    if (source == null) {
      return pages;
    }
    for (int i = 0; i < source.length(); i++) {
      JSONObject page = source.optJSONObject(i);
      if (page == null) {
        continue;
      }
      List<String> lines = pageLines(page);
      JSONObject legacy = new JSONObject();
      legacy.put("words_result", toWords(lines));
      if (i == 0) {
        legacy.put("general_words_result", new JSONArray(lines));
      }
      legacy.put("tables_result", toTables(page, lines));
      pages.put(legacy);
    }
    return pages;
  }

  /**
   * 页面文本行：优先用 page.text，没有时退化为 layouts 里的段落/标题文本。
   */
  private static List<String> pageLines(JSONObject page) {
    List<String> lines = new ArrayList<>();
    String text = stringValue(page, "text");
    if (!text.isBlank()) {
      // 接口返回的换行可能是字面量 "\n"（反斜杠 + n），先还原成真实换行再逐行拆分
      String normalized = text.replace("\\r\\n", "\n").replace("\\n", "\n")
        .replace("\\r", "\n").replace("\\t", " ");
      for (String line : normalized.split("\\r?\\n")) {
        String t = clean(line);
        if (!t.isEmpty()) {
          lines.add(t);
        }
      }
    }
    if (!lines.isEmpty()) {
      return lines;
    }
    JSONArray layouts = page.optJSONArray("layouts");
    if (layouts != null) {
      for (int i = 0; i < layouts.length(); i++) {
        JSONObject layout = layouts.optJSONObject(i);
        if (layout == null) {
          continue;
        }
        String type = layout.optString("type", "");
        if ("table".equals(type) || "image".equals(type)) {
          continue;
        }
        String t = clean(stringValue(layout, "text"));
        if (!t.isEmpty()) {
          lines.add(t);
        }
      }
    }
    return lines;
  }

  private static JSONArray toWords(List<String> lines) {
    JSONArray words = new JSONArray();
    for (String line : lines) {
      JSONObject item = new JSONObject();
      item.put("words", line);
      words.put(item);
    }
    return words;
  }

  /**
   * 页脚合计行（本页收支算术合计、本页交易笔数），供解析器做逐页对账与纠错。
   */
  private static JSONArray toFooterWords(List<String> lines) {
    JSONArray words = new JSONArray();
    for (String line : lines) {
      if (line.contains("合计") || line.contains("交易笔数")) {
        JSONObject item = new JSONObject();
        item.put("words", line);
        words.put(item);
      }
    }
    return words;
  }

  private static JSONArray toTables(JSONObject page, List<String> pageLines) {
    JSONArray result = new JSONArray();
    JSONArray tables = page.optJSONArray("tables");
    if (tables == null) {
      return result;
    }
    for (int t = 0; t < tables.length(); t++) {
      JSONObject table = tables.optJSONObject(t);
      if (table == null) {
        continue;
      }
      JSONArray body = toBody(table);
      if (body.length() == 0) {
        body = fromMarkdown(stringValue(table, "markdown"));
      }
      if (body.length() == 0) {
        continue;
      }
      JSONObject legacy = new JSONObject();
      // 表格上方的户名/账号/日期等文本，供解析器填表头
      legacy.put("header", toWords(pageLines));
      legacy.put("body", body);
      legacy.put("footer", toFooterWords(pageLines));
      result.put(legacy);
    }
    return result;
  }

  /**
   * 展开 cells + matrix：matrix[r][c] 是 cells 下标，-1 表示空（合并单元格可能是数组）。
   */
  private static JSONArray toBody(JSONObject table) {
    JSONArray body = new JSONArray();
    JSONArray cells = table.optJSONArray("cells");
    if (cells == null || cells.length() == 0) {
      return body;
    }
    JSONArray matrix = table.optJSONArray("matrix");
    if (matrix == null) {
      // 没有 matrix 时按顺序单列展开，至少不丢内容
      for (int i = 0; i < cells.length(); i++) {
        JSONObject cell = cells.optJSONObject(i);
        String text = cellText(cell);
        if (!text.isEmpty()) {
          body.put(cellItem(text, i, 0));
        }
      }
      return body;
    }
    for (int r = 0; r < matrix.length(); r++) {
      JSONArray row = matrix.optJSONArray(r);
      if (row == null) {
        continue;
      }
      for (int c = 0; c < row.length(); c++) {
        int index = cellIndex(row.opt(c));
        if (index < 0 || index >= cells.length()) {
          continue;
        }
        String text = cellText(cells.optJSONObject(index));
        if (!text.isEmpty()) {
          body.put(cellItem(text, r, c));
        }
      }
    }
    return body;
  }

  private static JSONObject cellItem(String words, int row, int col) {
    JSONObject item = new JSONObject();
    item.put("words", words);
    item.put("row_start", row);
    item.put("col_start", col);
    return item;
  }

  /**
   * 单元格文本：cells 里的元素可能是 {text}、{words}、{content} 或内嵌 layouts。
   */
  private static String cellText(JSONObject cell) {
    if (cell == null) {
      return "";
    }
    for (String key : new String[]{"text", "words", "content", "markdown"}) {
      String value = joinNumberFragments(clean(stringValue(cell, key)));
      if (!value.isEmpty()) {
        return value;
      }
    }
    JSONArray layouts = cell.optJSONArray("layouts");
    if (layouts != null) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < layouts.length(); i++) {
        JSONObject layout = layouts.optJSONObject(i);
        String value = clean(layout == null ? "" : stringValue(layout, "text"));
        if (value.isEmpty()) {
          continue;
        }
        if (sb.length() > 0) {
          sb.append(' ');
        }
        sb.append(value);
      }
      return joinNumberFragments(sb.toString());
    }
    return "";
  }

  /**
   * 数字被换行拆开时（如 {@code 14.\n080.72}）会被还原成 "14. 080.72"，
   * 这里去掉小数点/逗号后的空格，恢复成 "14.080.72"，避免金额只取到后半段。
   */
  private static String joinNumberFragments(String value) {
    return value == null ? "" : value.replaceAll("(?<=[.,])\\s+(?=\\d)", "");
  }

  /** 无 cells 时解析 markdown 管道表格（html_table_format=false 时的返回） */
  private static JSONArray fromMarkdown(String markdown) {
    JSONArray body = new JSONArray();
    if (markdown == null || markdown.isBlank() || !markdown.contains("|")) {
      return body;
    }
    int rowIndex = 0;
    for (String line : markdown.split("\\r?\\n")) {
      String t = line.trim();
      if (t.isEmpty() || !t.contains("|")) {
        continue;
      }
      if (t.matches("^[|\\s:\\-]+$")) {
        continue; // 分隔行 |---|---|
      }
      String[] parts = t.split("\\|", -1);
      int start = parts.length > 0 && parts[0].isBlank() ? 1 : 0;
      int end = parts.length > start && parts[parts.length - 1].isBlank()
        ? parts.length - 1 : parts.length;
      for (int c = start; c < end; c++) {
        String value = clean(parts[c]);
        if (!value.isEmpty()) {
          body.put(cellItem(value, rowIndex, c - start));
        }
      }
      rowIndex++;
    }
    return body;
  }

  private static int cellIndex(Object value) {
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof JSONArray array && array.length() > 0) {
      Object first = array.opt(0);
      if (first instanceof Number number) {
        return number.intValue();
      }
    }
    return -1;
  }

  private static String stringValue(JSONObject obj, String key) {
    Object value = obj.opt(key);
    return value instanceof String s ? s : "";
  }

  private static String clean(String value) {
    if (value == null) {
      return "";
    }
    // 接口部分文本里是字面量 "\n"/"\t"（反斜杠 + n），先还原成空格，再处理真实换行
    return value.replace("\\r", " ").replace("\\n", " ").replace("\\t", " ")
      .replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
      .trim();
  }
}
