import { useCallback, useRef, useState } from 'react';
import { getRiskTags } from '@/services/api';
import type { RiskTagItem } from '@/types';

/** 与后端一致的名称归一化：去掉所有空白（含全角空格）并转小写 */
export const riskNameKey = (name?: string | null) =>
  (name || '').replace(/[\s\u3000]+/g, '').toLowerCase();

/**
 * 风险标签批量加载：页面拿到一批名称后一次请求取回标签映射，避免逐行请求。
 * 分页追加时多次 load 会合并结果。
 */
export function useRiskTags(subjectType: 'CUSTOMER' | 'COUNTERPARTY') {
  const [tagMap, setTagMap] = useState<Record<string, RiskTagItem[]>>({});
  const mapRef = useRef<Record<string, RiskTagItem[]>>({});

  const load = useCallback(
    (names: (string | undefined | null)[]) => {
      const unique = Array.from(
        new Set(names.filter((name): name is string => !!name && name.trim() !== ''))
      );
      if (unique.length === 0) {
        return;
      }
      getRiskTags(subjectType, unique)
        .then((result) => {
          mapRef.current = { ...mapRef.current, ...(result || {}) };
          setTagMap(mapRef.current);
        })
        .catch((err) => console.error('[RiskTags] 标签加载失败', err));
    },
    [subjectType]
  );

  const tagsFor = useCallback(
    (name?: string | null): RiskTagItem[] => tagMap[riskNameKey(name)] || [],
    [tagMap]
  );

  return { load, tagsFor };
}
