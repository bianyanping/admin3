import React from 'react';
import { Text, View } from '@tarojs/components';
import styles from './index.module.scss';
import type { Transaction, RiskTagItem } from '@/types';
import { formatMoney, formatSignedMoney } from '@/utils/format';
import RiskTags from '@/components/RiskTags';

export interface TransactionItemProps {
  transaction: Transaction;
  /** 对方户名对应的风险标签，由明细页批量取回后传入 */
  tags?: RiskTagItem[];
}

const TransactionItem: React.FC<TransactionItemProps> = ({ transaction, tags }) => {
  const isIncome = transaction.amount >= 0;

  return (
    <View className={styles.item}>
      <View className={styles.left}>
        <View className={styles.titleRow}>
          <Text className={styles.title}>
            {transaction.counterpartyName || transaction.summary || '未知交易'}
          </Text>
          <RiskTags tags={tags} />
        </View>
        <Text className={styles.meta}>
          {transaction.transDate}
          {transaction.transTime ? ` ${transaction.transTime}` : ''}
          {transaction.summary ? ` · ${transaction.summary}` : ''}
        </Text>
      </View>
      <View className={styles.right}>
        <Text className={isIncome ? styles.income : styles.expense}>
          {formatSignedMoney(transaction.amount)}
        </Text>
        <Text className={styles.balance}>
          余额 {transaction.balance != null ? formatMoney(transaction.balance) : '--'}
        </Text>
      </View>
    </View>
  );
};

export default TransactionItem;
