import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Input, Text, View } from '@tarojs/components';
import Taro, { usePullDownRefresh, useReachBottom, useRouter } from '@tarojs/taro';
import classnames from 'classnames';
import styles from './index.module.scss';
import TransactionItem from '@/components/TransactionItem';
import EmptyState from '@/components/EmptyState';
import { getStatementDetail, getTransactions } from '@/services/api';
import { useRiskTags } from '@/hooks/useRiskTags';
import RiskTags from '@/components/RiskTags';
import type { Statement, Transaction } from '@/types';
import { formatMoney, maskAccount } from '@/utils/format';

const PAGE_SIZE = 20;

const DIRECTION_FILTERS = [
  { label: '全部', value: '' },
  { label: '收入', value: '入' },
  { label: '支出', value: '出' }
];

interface FilterOverride {
  direction?: string;
  keyword?: string;
}

const StatementDetailPage: React.FC = () => {
  const router = useRouter();
  const statementId = router.params?.id || '';

  const [statement, setStatement] = useState<Statement | null>(null);
  const [list, setList] = useState<Transaction[]>([]);
  const [total, setTotal] = useState(0);
  const [direction, setDirection] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const pageRef = useRef(1);
  const finishedRef = useRef(false);
  const loadingRef = useRef(false);
  const customerTags = useRiskTags('CUSTOMER');
  const counterpartyTags = useRiskTags('COUNTERPARTY');

  const fetchList = useCallback(
    async (nextPage: number, reset = false, override: FilterOverride = {}) => {
      if (!statementId || loadingRef.current) return;
      loadingRef.current = true;
      setLoading(true);

      const activeDirection = override.direction !== undefined ? override.direction : direction;
      const activeKeyword = override.keyword !== undefined ? override.keyword : keyword;

      try {
        const result = await getTransactions({
          statementId,
          direction: activeDirection || undefined,
          keyword: activeKeyword.trim() || undefined,
          page: nextPage,
          size: PAGE_SIZE
        });
        console.info('[StatementDetail] 明细加载完成', {
          page: nextPage,
          size: result.list.length,
          total: result.total
        });
        setList((prev) => (reset ? result.list : [...prev, ...result.list]));
        setTotal(result.total);
        counterpartyTags.load(result.list.map((item) => item.counterpartyName));
        pageRef.current = nextPage;
        finishedRef.current = result.list.length < PAGE_SIZE;
      } catch (err) {
        console.error('[StatementDetail] 明细加载失败', err);
        Taro.showToast({ title: '加载失败，请重试', icon: 'none' });
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [direction, keyword, statementId]
  );

  useEffect(() => {
    if (!statementId) {
      Taro.showToast({ title: '缺少流水单参数', icon: 'none' });
      return;
    }
    getStatementDetail(statementId)
      .then((data) => {
        setStatement(data);
        customerTags.load([data.accountHolder]);
      })
      .catch((err) => console.error('[StatementDetail] 汇总信息加载失败', err));
    fetchList(1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statementId]);

  usePullDownRefresh(async () => {
    await fetchList(1, true);
    Taro.stopPullDownRefresh();
  });

  useReachBottom(() => {
    if (finishedRef.current || loadingRef.current) return;
    fetchList(pageRef.current + 1);
  });

  const handleDirection = (value: string) => {
    setDirection(value);
    fetchList(1, true, { direction: value });
  };

  const handleSearch = () => {
    fetchList(1, true, { keyword });
  };

  return (
    <View className={styles.page}>
      <View className={styles.summaryCard}>
        <Text className={styles.bank}>{statement?.bankName || '--'}</Text>
        <View className={styles.holderRow}>
          <Text className={styles.holder}>{statement?.accountHolder || '--'}</Text>
          <RiskTags tags={customerTags.tagsFor(statement?.accountHolder)} />
        </View>
        <Text className={styles.account}>{maskAccount(statement?.accountNo)}</Text>
        <View className={styles.periodRow}>
          <Text className={styles.period}>
            {statement?.startDate || '--'} ~ {statement?.endDate || '--'}
          </Text>
          <Text className={styles.count}>{statement?.transactionCount ?? 0} 笔</Text>
        </View>
        <View className={styles.amountRow}>
          <View className={styles.amountItem}>
            <Text className={styles.amountLabel}>总收入</Text>
            <Text className={styles.income}>{formatMoney(statement?.totalIncome)}</Text>
          </View>
          <View className={styles.amountLine} />
          <View className={styles.amountItem}>
            <Text className={styles.amountLabel}>总支出</Text>
            <Text className={styles.expense}>{formatMoney(statement?.totalExpense)}</Text>
          </View>
        </View>
      </View>

      <View className={styles.filterRow}>
        {DIRECTION_FILTERS.map((item) => (
          <View
            key={item.value || 'all'}
            className={classnames(
              styles.filterItem,
              direction === item.value && styles.filterItemActive
            )}
            onClick={() => handleDirection(item.value)}
          >
            <Text
              className={classnames(
                styles.filterText,
                direction === item.value && styles.filterTextActive
              )}
            >
              {item.label}
            </Text>
          </View>
        ))}
      </View>

      <View className={styles.searchBox}>
        <Input
          className={styles.searchInput}
          value={keyword}
          placeholder="搜索对方户名 / 摘要"
          placeholderClass={styles.placeholder}
          confirmType="search"
          onInput={(event) => setKeyword(event.detail.value)}
          onConfirm={handleSearch}
        />
      </View>

      <View className={styles.listHead}>
        <Text className={styles.listTitle}>交易明细</Text>
        <Text className={styles.listCount}>共 {total} 笔</Text>
      </View>

      <View className={styles.listCard}>
        {list.length === 0 && !loading ? (
          <EmptyState text="没有符合条件的交易" />
        ) : (
          list.map((item) => (
            <TransactionItem
              key={item._id}
              transaction={item}
              tags={counterpartyTags.tagsFor(item.counterpartyName)}
            />
          ))
        )}
      </View>

      {loading ? <Text className={styles.loadingText}>加载中...</Text> : null}
      {!loading && list.length > 0 && finishedRef.current ? (
        <Text className={styles.loadingText}>没有更多了</Text>
      ) : null}
    </View>
  );
};

export default StatementDetailPage;
