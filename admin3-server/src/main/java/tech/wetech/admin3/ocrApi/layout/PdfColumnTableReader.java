package tech.wetech.admin3.ocrApi.layout;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 按坐标还原 PDF 表格行。
 *
 * <p>{@link PDFTextStripper} 按“视觉行”输出文本，表格单元格一旦折行，同一行数据会被拆到
 * 多条视觉行里（例如对方账号折成 {@code 6600000190} + {@code 69700012}），逐行取 token 会
 * 截断或串列。本类改为：
 * <ol>
 *   <li>从表头文字位置算出各列 X 分界；</li>
 *   <li>用行锚点列（日期/记账日期）的 Y 坐标切分交易行；</li>
 *   <li>把每个字符按 Y 归行、按 X 归列后拼接，得到完整的单元格文本。</li>
 * </ol>
 */
public final class PdfColumnTableReader {

  /** 表头标签的 Y 容差 */
  private static final float HEADER_LINE_TOLERANCE = 3f;
  /** 没有表头的续页：首行内容可能比日期锚点高约 25pt，这里留 40pt 余量 */
  private static final float FIRST_ROW_MARGIN = 40f;
  private static final Pattern SUMMARY_ROW = Pattern.compile("(收入|支出)?\\s*(合计|总计|总金额|总笔数)|笔数");
  private static final Pattern PAGE_NOISE = Pattern.compile(
    "第\\s*\\d+\\s*页|生成时间|打印时间|查询时间|温馨提示|重要提示|本明细仅限");
  private static final Pattern MULTI_NUMBER = Pattern.compile("[-+]{0,2}[\\d,.]+\\s+[-+]?[\\d,.]+.*");

  private PdfColumnTableReader() {
  }

  public static final class Row {
    private final Map<String, String> cells;

    private Row(Map<String, String> cells) {
      this.cells = cells;
    }

    public String get(String field) {
      return cells.get(field);
    }

    public boolean has(String field) {
      return cells.containsKey(field);
    }
  }

  private static final class Glyph {
    final String text;
    final float x;
    final float y;

    Glyph(String text, float x, float y) {
      this.text = text;
      this.x = x;
      this.y = y;
    }
  }

  private static final class Chunk {
    final String text;
    final float x0;
    final float x1;
    final float y;

    Chunk(String text, float x0, float x1, float y) {
      this.text = text;
      this.x0 = x0;
      this.x1 = x1;
      this.y = y;
    }

    float center() {
      return (x0 + x1) / 2f;
    }
  }

  private static final class Run {
    final String text;
    final float x0;
    final float x1;
    final float y;
    final List<Glyph> glyphs;

    Run(String text, float x0, float x1, float y, List<Glyph> glyphs) {
      this.text = text;
      this.x0 = x0;
      this.x1 = x1;
      this.y = y;
      this.glyphs = glyphs;
    }
  }

  private static final class PageData {
    final List<Run> runs = new ArrayList<>();
    final List<Chunk> chunks = new ArrayList<>();
  }

  private static final class Layout {
    final List<String> fields;
    final float[] centers;
    final float[] boundaries;
    final float headerY;

    Layout(List<String> fields, float[] centers, float[] boundaries, float headerY) {
      this.fields = fields;
      this.centers = centers;
      this.boundaries = boundaries;
      this.headerY = headerY;
    }

    String fieldAt(float x) {
      for (int i = 0; i < boundaries.length; i++) {
        if (x < boundaries[i]) {
          return fields.get(i);
        }
      }
      return fields.get(fields.size() - 1);
    }

    /** 单字续行（如「款」「司」）贴近左边界时，按最近表头中心归属更稳 */
    String nearestCenterField(float x) {
      int best = 0;
      float bestDistance = Float.MAX_VALUE;
      for (int i = 0; i < centers.length; i++) {
        float distance = Math.abs(centers[i] - x);
        if (distance < bestDistance) {
          bestDistance = distance;
          best = i;
        }
      }
      return fields.get(best);
    }

    /** 长文本可能压过列分界线，按多数字符所在列归属 */
    String fieldOf(Run run) {
      if (run.glyphs.size() == 1) {
        return nearestCenterField(run.x1);
      }
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (Glyph glyph : run.glyphs) {
        counts.merge(fieldAt(glyph.x), 1, Integer::sum);
      }
      String best = fields.get(0);
      int bestCount = -1;
      for (Map.Entry<String, Integer> e : counts.entrySet()) {
        if (e.getValue() > bestCount) {
          bestCount = e.getValue();
          best = e.getKey();
        }
      }
      return best;
    }
  }

  /**
   * 读取整份 PDF 的表格行。
   *
   * @param labels       表头文字 → 字段名
   * @param required     必须识别到的字段，缺一列就不启用坐标重建
   * @param anchorField  行锚点所在字段（通常是交易时间/记账日期）
   * @param anchorPattern 行锚点文本的完整匹配正则
   */
  public static List<Row> read(PDDocument doc, Map<String, String> labels, Set<String> required,
                               String anchorField, Pattern anchorPattern) throws IOException {
    List<PageData> pages = collect(doc);
    Layout layout = detectLayout(pages, labels, required);
    if (layout == null) {
      return Collections.emptyList();
    }
    List<Row> rows = new ArrayList<>();
    for (PageData page : pages) {
      rows.addAll(extractPage(page, layout, labels, anchorField, anchorPattern));
    }
    return rows;
  }

  private static List<PageData> collect(PDDocument doc) throws IOException {
    int pageCount = doc.getNumberOfPages();
    List<PageData> pages = new ArrayList<>(pageCount);
    for (int i = 0; i < pageCount; i++) {
      pages.add(new PageData());
    }
    final int[] current = {0};
    PDFTextStripper stripper = new PDFTextStripper() {
      @Override
      protected void writeString(String text, List<TextPosition> positions) {
        int index = current[0] - 1;
        if (positions == null || positions.isEmpty() || index < 0 || index >= pages.size()) {
          return;
        }
        PageData page = pages.get(index);
        StringBuilder sb = new StringBuilder();
        List<Glyph> glyphs = new ArrayList<>();
        float x0 = Float.MAX_VALUE;
        float x1 = -Float.MAX_VALUE;
        float y = positions.get(0).getYDirAdj();
        for (TextPosition tp : positions) {
          String unicode = tp.getUnicode();
          if (unicode == null || unicode.isEmpty()) {
            continue;
          }
          glyphs.add(new Glyph(unicode, tp.getXDirAdj() + tp.getWidthDirAdj() / 2f, tp.getYDirAdj()));
          sb.append(unicode);
          x0 = Math.min(x0, tp.getXDirAdj());
          x1 = Math.max(x1, tp.getXDirAdj() + tp.getWidthDirAdj());
        }
        String value = sb.toString().trim();
        if (!value.isEmpty()) {
          page.runs.add(new Run(value, x0, x1, y, glyphs));
          page.chunks.add(new Chunk(value, x0, x1, y));
        }
      }

      @Override
      protected void writePage() throws IOException {
        current[0]++;
        super.writePage();
      }
    };
    stripper.setSortByPosition(true);
    stripper.setStartPage(1);
    stripper.setEndPage(pageCount);
    stripper.getText(doc);
    for (PageData page : pages) {
      page.runs.sort(Comparator.comparingDouble((Run r) -> r.y).thenComparingDouble(r -> r.x0));
      page.chunks.sort(Comparator.comparingDouble((Chunk c) -> c.y).thenComparingDouble(c -> c.x0));
    }
    return pages;
  }

  private static Layout detectLayout(List<PageData> pages, Map<String, String> labels,
                                     Set<String> required) {
    for (PageData page : pages) {
      float top = Float.MAX_VALUE;
      for (Chunk c : page.chunks) {
        if (labels.containsKey(c.text)) {
          top = Math.min(top, c.y);
        }
      }
      if (top == Float.MAX_VALUE) {
        continue;
      }
      Map<String, Float> centers = new LinkedHashMap<>();
      for (Chunk c : page.chunks) {
        String field = labels.get(c.text);
        if (field != null && c.y <= top + HEADER_LINE_TOLERANCE) {
          centers.putIfAbsent(field, c.center());
        }
      }
      if (!centers.keySet().containsAll(required)) {
        continue;
      }
      List<Map.Entry<String, Float>> sorted = new ArrayList<>(centers.entrySet());
      sorted.sort(Comparator.comparingDouble(Map.Entry::getValue));
      List<String> fields = new ArrayList<>(sorted.size());
      float[] centerValues = new float[sorted.size()];
      for (int i = 0; i < sorted.size(); i++) {
        fields.add(sorted.get(i).getKey());
        centerValues[i] = sorted.get(i).getValue();
      }
      float[] boundaries = new float[sorted.size() - 1];
      for (int i = 0; i < boundaries.length; i++) {
        boundaries[i] = (sorted.get(i).getValue() + sorted.get(i + 1).getValue()) / 2f;
      }
      return new Layout(fields, centerValues, boundaries, top);
    }
    return null;
  }

  private static List<Row> extractPage(PageData page, Layout layout, Map<String, String> labels,
                                       String anchorField, Pattern anchorPattern) {
    float pageHeaderY = Float.MAX_VALUE;
    for (Chunk c : page.chunks) {
      if (labels.containsKey(c.text)) {
        pageHeaderY = Math.min(pageHeaderY, c.y);
      }
    }
    boolean hasHeader = pageHeaderY != Float.MAX_VALUE;

    List<Chunk> anchors = new ArrayList<>();
    for (Chunk c : page.chunks) {
      if (hasHeader && c.y <= pageHeaderY + HEADER_LINE_TOLERANCE) {
        continue;
      }
      if (anchorField.equals(layout.fieldAt(c.center())) && anchorPattern.matcher(c.text).matches()) {
        anchors.add(c);
      }
    }
    if (anchors.isEmpty()) {
      return Collections.emptyList();
    }
    anchors.sort(Comparator.comparingDouble(c -> c.y));

    float dataTop = hasHeader ? pageHeaderY + HEADER_LINE_TOLERANCE : anchors.get(0).y - FIRST_ROW_MARGIN;
    float dataBottom = Float.MAX_VALUE;
    for (Chunk c : page.chunks) {
      if (c.y <= dataTop) {
        continue;
      }
      if (PAGE_NOISE.matcher(c.text).find() || SUMMARY_ROW.matcher(c.text).find()) {
        dataBottom = Math.min(dataBottom, c.y - 4f);
      }
    }

    List<Chunk> validAnchors = new ArrayList<>();
    for (Chunk c : anchors) {
      if (c.y > dataTop && c.y < dataBottom) {
        validAnchors.add(c);
      }
    }
    if (validAnchors.isEmpty()) {
      return Collections.emptyList();
    }
    float[] bounds = new float[validAnchors.size() - 1];
    for (int i = 0; i < bounds.length; i++) {
      bounds[i] = (validAnchors.get(i).y + validAnchors.get(i + 1).y) / 2f;
    }

    List<Map<String, StringBuilder>> cells = new ArrayList<>(validAnchors.size());
    for (int i = 0; i < validAnchors.size(); i++) {
      cells.add(new LinkedHashMap<>());
    }
    for (Run run : page.runs) {
      if (run.y <= dataTop || run.y >= dataBottom) {
        continue;
      }
      int row = rowIndex(bounds, run.y);
      Map<String, StringBuilder> rowCells = cells.get(row);
      if (MULTI_NUMBER.matcher(run.text).matches()) {
        // 例如 "-600,000.00 2,191,066.18" 被 pdfbox 并成一段，需按字符 x 分列
        for (Glyph glyph : run.glyphs) {
          rowCells.computeIfAbsent(layout.fieldAt(glyph.x), k -> new StringBuilder()).append(glyph.text);
        }
      } else {
        // 文本段按多数字符归列，避免长户名/长账号溢出到相邻列时被切断
        rowCells.computeIfAbsent(layout.fieldOf(run), k -> new StringBuilder()).append(run.text);
      }
    }

    List<Row> rows = new ArrayList<>(cells.size());
    for (Map<String, StringBuilder> cell : cells) {
      Map<String, String> values = new LinkedHashMap<>();
      for (Map.Entry<String, StringBuilder> e : cell.entrySet()) {
        String value = e.getValue().toString().replaceAll("[\\s\\u00A0\\u3000]+", " ").trim();
        if (!value.isEmpty()) {
          values.put(e.getKey(), value);
        }
      }
      rows.add(new Row(values));
    }
    return rows;
  }

  private static int rowIndex(float[] bounds, float y) {
    for (int i = 0; i < bounds.length; i++) {
      if (y < bounds[i]) {
        return i;
      }
    }
    return bounds.length;
  }
}
