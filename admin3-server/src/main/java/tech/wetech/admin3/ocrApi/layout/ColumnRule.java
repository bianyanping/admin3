package tech.wetech.admin3.ocrApi.layout;

import tech.wetech.admin3.DTO.TransactionDTO;

import java.util.function.BiConsumer;
import java.util.regex.Pattern;

public class ColumnRule {

  private final String fieldName;                          // 语义字段名，用于计数
  private final Pattern pattern;                           // 匹配模式
  private final int occurrence;                            // 第几次匹配时填充
  private final BiConsumer<TransactionDTO, String> setter; // 怎么写入 DTO

  public ColumnRule(String fieldName, Pattern pattern, int occurrence,
                    BiConsumer<TransactionDTO, String> setter) {
    this.fieldName = fieldName;
    this.pattern = pattern;
    this.occurrence = occurrence;
    this.setter = setter;
  }

  public boolean matches(String word) {
    return pattern != null && pattern.matcher(word).matches();
  }

  public String getFieldName() { return fieldName; }
  public int getOccurrence() { return occurrence; }
  public BiConsumer<TransactionDTO, String> getSetter() { return setter; }
}
