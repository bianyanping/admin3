import React from 'react';
import { Text, View } from '@tarojs/components';
import styles from './index.module.scss';

export interface EmptyStateProps {
  /** 提示文案 */
  text?: string;
}

const EmptyState: React.FC<EmptyStateProps> = ({ text = '暂无数据' }) => (
  <View className={styles.empty}>
    <View className={styles.icon}>
      <Text className={styles.iconText}>—</Text>
    </View>
    <Text className={styles.text}>{text}</Text>
  </View>
);

export default EmptyState;
