import {ref} from 'vue';
import {batchRiskTags} from '../api/risk';

export interface RiskTagItem {
  id: number;
  code: string;
  name: string;
  color: string;
  subjectType: string;
  source: string;
  builtin: boolean;
  description: string;
}

/**
 * 与后端一致的名字清洗：去掉所有空白（含全角空格），英文小写。
 */
export const riskNameKey = (name?: string | null) =>
  (name || '').replace(/[\s\u3000]+/g, '').toLowerCase();

/**
 * 页面内批量加载户名标签：调用方收集当前页出现的名称，一次请求取回，避免 N+1。
 */
export function useRiskTags(subjectType: 'COUNTERPARTY' | 'CUSTOMER') {
  const tagMap = ref<Record<string, RiskTagItem[]>>({});

  const load = (names: Array<string | null | undefined>) => {
    const unique = Array.from(new Set(names.filter((n): n is string => !!n && n.trim() !== '')));
    if (unique.length === 0) {
      return Promise.resolve();
    }
    return batchRiskTags(subjectType, unique)
      .then((res: any) => {
        // 分多次加载时合并结果，避免后一次加载覆盖前一次
        tagMap.value = {...tagMap.value, ...(res.data || {})};
      })
      .catch(() => {
        // 标签取数失败不阻塞页面主体数据展示
      });
  };

  const tagsFor = (name?: string | null): RiskTagItem[] =>
    tagMap.value[riskNameKey(name)] || [];

  /**
   * 单个/少量主体打标或解除后刷新：先清掉这些名称的旧结果再合并，
   * 避免解除标签后批量接口不返回该名称时仍保留旧标签。
   */
  const refresh = (names: Array<string | null | undefined>) => {
    const unique = Array.from(new Set(names.filter((n): n is string => !!n && n.trim() !== '')));
    if (unique.length === 0) {
      return Promise.resolve();
    }
    return batchRiskTags(subjectType, unique)
      .then((res: any) => {
        const next = {...tagMap.value};
        for (const name of unique) {
          delete next[riskNameKey(name)];
        }
        tagMap.value = {...next, ...(res.data || {})};
      })
      .catch(() => {
        // 标签取数失败不阻塞页面主体数据展示
      });
  };

  return {tagMap, load, refresh, tagsFor};
}
