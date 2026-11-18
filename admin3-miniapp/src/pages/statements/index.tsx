import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Input, Text, View } from '@tarojs/components';
import Taro, { usePullDownRefresh, useReachBottom } from '@tarojs/taro';
import classnames from 'classnames';
import styles from './index.module.scss';
import StatementCard from '@/components/StatementCard';
import EmptyState from '@/components/EmptyState';
import { getStatements } from '@/services/api';
import { useRiskTags } from '@/hooks/useRiskTags';
import type { Statement } from '@/types';

const PAGE_SIZE = 10;

const BANK_FILTERS = [
  { label: '全部', value: '' },
  { label: '建设银行', value: 'CCB' },
  { label: '农业银行', value: 'ABCHX' },
  { label: '邮储银行', value: 'PSBC' },
  { label: '广西农商联合银行', value: 'GXRCB' },
  { label: '桂林银行', value: 'GLYH' },
  { label: '微信支付', value: 'WECHAT' }
];

interface FilterOverride {
  keyword?: string;
  bankCode?: string;
}

const StatementsPage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [bankCode, setBankCode] = useState('');
  const [list, setList] = useState<Statement[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const pageRef = useRef(1);
  const finishedRef = useRef(false);
  const loadingRef = useRef(false);
  const customerTags = useRiskTags('CUSTOMER');

  const fetchList = useCallback(
    async (nextPage: number, reset = false, override: FilterOverride = {}) => {
      if (loadingRef.current) return;
      loadingRef.current = true;
      setLoading(true);

      const activeKeyword = override.keyword !== undefined ? override.keyword : keyword;
      const activeBank = override.bankCode !== undefined ? override.bankCode : bankCode;

      try {
        const result = await getStatements({
          keyword: activeKeyword.trim() || undefined,
          bankCode: activeBank || undefined,
          page: nextPage,
          size: PAGE_SIZE
        });
        console.info('[Statements] 列表加载完成', {
          page: nextPage,
          size: result.list.length,
          total: result.total
        });
        setList((prev) => (reset ? result.list : [...prev, ...result.list]));
        setTotal(result.total);
        customerTags.load(result.list.map((item) => item.accountHolder));
        pageRef.current = nextPage;
        finishedRef.current = result.list.length < PAGE_SIZE;
      } catch (err) {
        console.error('[Statements] 列表加载失败', err);
        Taro.showToast({ title: '加载失败，请重试', icon: 'none' });
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [bankCode, keyword]
  );

  useEffect(() => {
    fetchList(1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearch = () => {
    fetchList(1, true, { keyword });
  };

  const handleBankChange = (value: string) => {
    setBankCode(value);
    fetchList(1, true, { bankCode: value });
  };

  usePullDownRefresh(async () => {
    await fetchList(1, true);
    Taro.stopPullDownRefresh();
  });

  useReachBottom(() => {
    if (finishedRef.current || loadingRef.current) return;
    fetchList(pageRef.current + 1);
  });

  const openDetail = (statement: Statement) => {
    Taro.navigateTo({ url: `/pages/statement-detail/index?id=${statement._id}` });
  };

  return (
    <View className={styles.page}>
      <View className={styles.toolbar}>
        <View className={styles.searchBox}>
          <Text className={styles.searchIcon}>⌕</Text>
          <Input
            className={styles.searchInput}
            value={keyword}
            placeholder="搜索户名或账号"
            placeholderClass={styles.placeholder}
            confirmType="search"
            onInput={(event) => setKeyword(event.detail.value)}
            onConfirm={handleSearch}
          />
        </View>
        <View className={styles.searchBtn} onClick={handleSearch}>
          <Text className={styles.searchBtnText}>搜索</Text>
        </View>
      </View>

      <View className={styles.filterRow}>
        {BANK_FILTERS.map((item) => (
          <View
            key={item.value || 'all'}
            className={classnames(
              styles.filterItem,
              bankCode === item.value && styles.filterItemActive
            )}
            onClick={() => handleBankChange(item.value)}
          >
            <Text
              className={classnames(
                styles.filterText,
                bankCode === item.value && styles.filterTextActive
              )}
            >
              {item.label}
            </Text>
          </View>
        ))}
      </View>

      <View className={styles.summary}>
        <Text className={styles.summaryText}>共 {total} 份流水单</Text>
      </View>

      <View className={styles.list}>
        {list.map((item) => (
          <StatementCard
            key={item._id}
            statement={item}
            tags={customerTags.tagsFor(item.accountHolder)}
            onClick={openDetail}
          />
        ))}
      </View>

      {!loading && list.length === 0 ? <EmptyState text="没有找到匹配的流水单" /> : null}
      {loading ? <Text className={styles.loadingText}>加载中...</Text> : null}
      {!loading && list.length > 0 && finishedRef.current ? (
        <Text className={styles.loadingText}>没有更多了</Text>
      ) : null}
    </View>
  );
};

export default StatementsPage;
