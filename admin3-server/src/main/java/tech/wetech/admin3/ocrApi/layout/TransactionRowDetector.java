package tech.wetech.admin3.ocrApi.layout;

import java.util.List;

public interface TransactionRowDetector {
  /** words[i] 是否是一条新交易的开头 */
  boolean isRowStart(List<String> words, int i);
  /** 从 from 开始找下一条交易开头的下标，找不到返回 words.size() */
  int findNextRow(List<String> words, int from);
}
