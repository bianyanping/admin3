import React from 'react';
import { Text, View } from '@tarojs/components';
import styles from './index.module.scss';
import type { Statement, RiskTagItem } from '@/types';
import { formatMoney, maskAccount } from '@/utils/format';
import RiskTags from '@/components/RiskTags';

export interface StatementCardProps {
  statement: Statement;
  /** 户名对应的风险标签，由列表页批量取回后传入 */
  tags?: RiskTagItem[];
  onClick?: (statement: Statement) => void;
}

const StatementCard: React.FC<StatementCardProps> = ({ statement, tags, onClick }) => (
  <View className={styles.card} onClick={() => onClick?.(statement)}>
    <View className={styles.head}>
      <Text className={styles.bank}>{statement.bankName}</Text>
      <Text className={styles.count}>{statement.transactionCount} 笔</Text>
    </View>

    <View className={styles.holderRow}>
      <Text className={styles.holder}>{statement.accountHolder}</Text>
      <RiskTags tags={tags} />
    </View>
    <Text className={styles.account}>{maskAccount(statement.accountNo)}</Text>

    <View className={styles.period}>
      <Text className={styles.periodText}>
        {statement.startDate} ~ {statement.endDate}
      </Text>
      <Text className={styles.currency}>{statement.currency}</Text>
    </View>

    <View className={styles.amountRow}>
      <View className={styles.amountItem}>
        <Text className={styles.amountLabel}>收入</Text>
        <Text className={styles.income}>{formatMoney(statement.totalIncome)}</Text>
      </View>
      <View className={styles.divider} />
      <View className={styles.amountItem}>
        <Text className={styles.amountLabel}>支出</Text>
        <Text className={styles.expense}>{formatMoney(statement.totalExpense)}</Text>
      </View>
    </View>
  </View>
);

export default StatementCard;
