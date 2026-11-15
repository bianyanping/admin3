package tech.wetech.admin3.ocrApi.layout;

import java.math.BigDecimal;

public final class AmountNormalizer {

  private AmountNormalizer() {
  }

  public static BigDecimal normalize(String s) {
    String clean = s.trim().replace(",", "")          // 去逗号千分位
      .replace("+", "").replace("T", "7")         // OCR: T -> 7
      .replace("l", "1").replace("I", "1").replace("O", "0").replace("S", "5");

    // 点千分位 + 点小数：600.000.00 -> 600000.00
    // 规则：最后一个点之后是 2 位小数，之前的点全是千分位
    int lastDot = clean.lastIndexOf('.');
    if (lastDot > 0 && clean.length() - lastDot - 1 == 2) {
      String intPart = clean.substring(0, lastDot).replace(".", "");
      String decPart = clean.substring(lastDot + 1);
      clean = intPart + "." + decPart;
    }
    return new BigDecimal(clean);
  }
}
