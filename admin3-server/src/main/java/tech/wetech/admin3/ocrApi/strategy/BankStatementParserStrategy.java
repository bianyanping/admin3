package tech.wetech.admin3.ocrApi.strategy;

import org.json.JSONArray;
import tech.wetech.admin3.DTO.BankStatementDTO;

public interface BankStatementParserStrategy {

  String bankCode();

  boolean supports(ParseContext context);

  BankStatementDTO parse(JSONArray pages);

  default int priority() { return 0; }
}
