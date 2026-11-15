import React from 'react';
import { Text, View } from '@tarojs/components';
import classnames from 'classnames';
import styles from './index.module.scss';

export interface StatCardProps {
  /** 指标名称 */
  label: string;
  /** 指标值（已格式化） */
  value: string;
  /** 补充说明 */
  hint?: string;
  /** 语义色调 */
  tone?: 'default' | 'income' | 'expense' | 'warn';
}

const StatCard: React.FC<StatCardProps> = ({ label, value, hint, tone = 'default' }) => (
  <View className={classnames(styles.card, styles[tone])}>
    <Text className={styles.value}>{value}</Text>
    <Text className={styles.label}>{label}</Text>
    {hint ? <Text className={styles.hint}>{hint}</Text> : null}
  </View>
);

export default StatCard;
