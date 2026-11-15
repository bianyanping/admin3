import React from 'react';
import { Text, View } from '@tarojs/components';
import styles from './index.module.scss';
import type { Transaction } from '@/types';
import { formatMoney, formatSignedMoney } from '@/utils/format';

export interface TransactionItemProps {
  transaction: Transaction;
}

const TransactionItem: React.FC<TransactionItemProps> = ({ transaction }) => {
  const isIncome = transaction.amount >= 0;

  return (
    <View className={styles.item}>
      <View className={styles.left}>
        <Text className={styles.title}>
          {transaction.counterpartyName || transaction.summary || '未知交易'}
        </Text>
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
