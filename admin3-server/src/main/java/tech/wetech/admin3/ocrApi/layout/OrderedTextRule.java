package tech.wetech.admin3.ocrApi.layout;

import tech.wetech.admin3.DTO.TransactionDTO;

import java.util.List;
import java.util.function.BiConsumer;

public class OrderedTextRule {

  private final List<String> fieldOrder;
  private final List<BiConsumer<TransactionDTO, String>> setters;

  public OrderedTextRule(List<String> fieldOrder,
                         List<BiConsumer<TransactionDTO, String>> setters) {
    if (fieldOrder.size() != setters.size()) {
      throw new IllegalArgumentException("fieldOrder 与 setters 数量必须一致");
    }
    this.fieldOrder = fieldOrder;
    this.setters = setters;
  }

  public List<String> getFieldOrder() { return fieldOrder; }
  public List<BiConsumer<TransactionDTO, String>> getSetters() { return setters; }
}
