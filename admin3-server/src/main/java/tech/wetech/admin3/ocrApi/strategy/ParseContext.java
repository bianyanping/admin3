package tech.wetech.admin3.ocrApi.strategy;

import java.util.List;

public class ParseContext {

  private final List<String> allWords;
  private final List<String> firstPageWords;
  private final String fileName;
  private final String explicitBankCode;

  public ParseContext(List<String> allWords, List<String> firstPageWords,
                      String fileName, String explicitBankCode) {
    this.allWords = allWords;
    this.firstPageWords = firstPageWords;
    this.fileName = fileName;
    this.explicitBankCode = explicitBankCode;
  }

  public List<String> getAllWords() { return allWords; }
  public List<String> getFirstPageWords() { return firstPageWords; }
  public String getFileName() { return fileName; }
  public String getExplicitBankCode() { return explicitBankCode; }
}
