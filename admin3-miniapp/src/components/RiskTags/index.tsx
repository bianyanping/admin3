import React from 'react';
import { Text, View } from '@tarojs/components';
import classnames from 'classnames';
import styles from './index.module.scss';
import type { RiskTagItem } from '@/types';

export interface RiskTagsProps {
  tags?: RiskTagItem[];
}

const COLOR_CLASS: Record<string, string> = {
  danger: styles.tagDanger,
  warning: styles.tagWarning,
  success: styles.tagSuccess,
  primary: styles.tagPrimary,
  info: styles.tagInfo
};

/**
 * 风险标签：与 Web 端 RiskTags 组件同一套配色，
 * 名称后紧跟标签，系统规则标签用虚线边框区分。
 */
const RiskTags: React.FC<RiskTagsProps> = ({ tags }) => {
  if (!tags || tags.length === 0) {
    return null;
  }
  return (
    <View className={styles.tags}>
      {tags.map((tag) => (
        <Text
          key={`${tag.id}-${tag.source}`}
          className={classnames(
            styles.tag,
            COLOR_CLASS[tag.color || 'info'] || styles.tagInfo,
            tag.source === 'SYSTEM' && styles.tagSystem
          )}
        >
          {tag.name}
          {tag.source === 'SYSTEM' ? <Text className={styles.source}>系统</Text> : null}
        </Text>
      ))}
    </View>
  );
};

export default RiskTags;
