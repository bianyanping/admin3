import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, View } from '@tarojs/components';
import Taro, { usePullDownRefresh } from '@tarojs/taro';
import classnames from 'classnames';
import styles from './index.module.scss';
import StatCard from '@/components/StatCard';
import EmptyState from '@/components/EmptyState';
import { getDashboard } from '@/services/api';
import { useRiskTags } from '@/hooks/useRiskTags';
import RiskTags from '@/components/RiskTags';
import { useAuthStore } from '@/store/auth';
import type { DashboardStats } from '@/types';
import { formatDayLabel, formatMoney, formatShortMoney, formatSignedMoney } from '@/utils/format';

const RANGE_OPTIONS = [
  { label: '近7天', value: 7 },
  { label: '近30天', value: 30 },
  { label: '近90天', value: 90 }
];

const HomePage: React.FC = () => {
  const profile = useAuthStore((state) => state.profile);
  const [days, setDays] = useState(30);
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(false);
  const counterpartyTags = useRiskTags('COUNTERPARTY');

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const data = await getDashboard(days);
      setStats(data);
      counterpartyTags.load((data.recentLarge || []).map((item) => item.counterpartyName));
      console.info('[Home] 看板数据加载完成', { days, totalCount: data.totalCount });
    } catch (err) {
      console.error('[Home] 看板数据加载失败', err);
      Taro.showToast({ title: '数据加载失败', icon: 'none' });
    } finally {
      setLoading(false);
    }
  }, [days]);

  useEffect(() => {
    load();
  }, [load]);

  usePullDownRefresh(async () => {
    await load();
    Taro.stopPullDownRefresh();
  });

  /** 趋势图：取最近 14 天，按最大值归一化为柱高 */
  const chartData = useMemo(() => {
    const daily = stats?.daily || [];
    const recent = daily.slice(-14);
    const max = recent.reduce(
      (acc, item) => Math.max(acc, item.income, item.expense),
      1
    );
    return recent.map((item) => ({
      date: formatDayLabel(item.date),
      incomeHeight: Math.max(Math.round((item.income / max) * 120), item.income > 0 ? 6 : 2),
      expenseHeight: Math.max(Math.round((item.expense / max) * 120), item.expense > 0 ? 6 : 2),
      income: item.income,
      expense: item.expense
    }));
  }, [stats]);

  const totalAmount = useMemo(
    () => Number(stats?.totalIncome || 0) + Number(stats?.totalExpense || 0),
    [stats]
  );

  /** 与 Web 端一致：只有拥有 bankStatement:analysis 权限才显示分析入口（* 为超管） */
  const canAnalysis = useMemo(() => {
    const permissions = profile?.permissions || [];
    return permissions.includes('*') || permissions.includes('bankStatement:analysis');
  }, [profile]);

  const openStatement = (statementId?: string) => {
    if (!statementId) return;
    Taro.navigateTo({ url: `/pages/statement-detail/index?id=${statementId}` });
  };

  return (
    <View className={styles.page}>
      {/* 顶部渐变头部 */}
      <View className={styles.header}>
        <View className={styles.headerTop}>
          <View>
            <Text className={styles.hello}>你好，{profile?.nickname || '访客'}</Text>
            <Text className={styles.subtitle}>今天是资金监控的第 {days} 天视图</Text>
          </View>
          <View className={styles.rangeGroup}>
            {RANGE_OPTIONS.map((option) => (
              <View
                key={option.value}
                className={classnames(
                  styles.rangeItem,
                  days === option.value && styles.rangeItemActive
                )}
                onClick={() => setDays(option.value)}
              >
                <Text className={styles.rangeText}>{option.label}</Text>
              </View>
            ))}
          </View>
        </View>
      </View>

      <View className={styles.body}>
        {/* 核心指标 */}
        <View className={styles.statGrid}>
          <StatCard label="总笔数" value={String(stats?.totalCount ?? 0)} />
          <StatCard label="总金额" value={formatShortMoney(totalAmount)} />
          <StatCard label="今日新增" value={String(stats?.todayCount ?? 0)} />
          <StatCard
            label="异常笔数"
            value={String(stats?.largeCount ?? 0)}
            hint="单笔 ≥ 5 万"
            tone="warn"
          />
        </View>

        {/* 收支汇总 */}
        <View className={styles.summaryCard}>
          <View className={styles.summaryItem}>
            <Text className={styles.summaryLabel}>总收入</Text>
            <Text className={styles.summaryIncome}>{formatMoney(stats?.totalIncome)}</Text>
          </View>
          <View className={styles.summaryLine} />
          <View className={styles.summaryItem}>
            <Text className={styles.summaryLabel}>总支出</Text>
            <Text className={styles.summaryExpense}>{formatMoney(stats?.totalExpense)}</Text>
          </View>
        </View>

        {/* 快捷入口：流水单分析 */}
        {canAnalysis ? (
          <View
            className={styles.quickEntry}
            onClick={() => Taro.navigateTo({ url: '/pages/analysis/index' })}
          >
            <View className={styles.quickLeft}>
              <Text className={styles.quickTitle}>流水单分析</Text>
              <Text className={styles.quickDesc}>按户名查看总入账/出款与每月收支</Text>
            </View>
            <Text className={styles.quickArrow}>›</Text>
          </View>
        ) : null}

        {/* 收支趋势 */}
        <View className={styles.card}>
          <View className={styles.cardHead}>
            <Text className={styles.cardTitle}>资金收支趋势</Text>
            <Text className={styles.cardSub}>最近 14 天</Text>
          </View>
          {chartData.length === 0 ? (
            <EmptyState text="暂无趋势数据" />
          ) : (
            <ScrollView scrollX className={styles.chartScroll}>
              <View className={styles.chartRow}>
                {chartData.map((item) => (
                  <View key={item.date} className={styles.chartColumn}>
                    <View className={styles.bars}>
                      <View
                        className={styles.barIncome}
                        style={{ height: `${item.incomeHeight}rpx` }}
                      />
                      <View
                        className={styles.barExpense}
                        style={{ height: `${item.expenseHeight}rpx` }}
                      />
                    </View>
                    <Text className={styles.chartLabel}>{item.date}</Text>
                  </View>
                ))}
              </View>
            </ScrollView>
          )}
          <View className={styles.legend}>
            <View className={styles.legendItem}>
              <View className={classnames(styles.dot, styles.dotIncome)} />
              <Text className={styles.legendText}>收入</Text>
            </View>
            <View className={styles.legendItem}>
              <View className={classnames(styles.dot, styles.dotExpense)} />
              <Text className={styles.legendText}>支出</Text>
            </View>
          </View>
        </View>

        {/* 流水构成 */}
        <View className={styles.card}>
          <View className={styles.cardHead}>
            <Text className={styles.cardTitle}>流水构成占比</Text>
          </View>
          {(stats?.categories || []).length === 0 ? (
            <EmptyState text="暂无构成数据" />
          ) : (
            (stats?.categories || []).map((item) => (
              <View key={item.name} className={styles.categoryItem}>
                <View className={styles.categoryLine}>
                  <Text className={styles.categoryName}>{item.name}</Text>
                  <Text className={styles.categoryPercent}>{item.percent.toFixed(1)}%</Text>
                </View>
                <View className={styles.progressTrack}>
                  <View
                    className={styles.progressBar}
                    style={{ width: `${Math.min(item.percent, 100)}%` }}
                  />
                </View>
              </View>
            ))
          )}
        </View>

        {/* 大额流水 */}
        <View className={styles.card}>
          <View className={styles.cardHead}>
            <Text className={styles.cardTitle}>最近大额流水</Text>
            <Text className={styles.cardSub}>点击查看明细</Text>
          </View>
          {(stats?.recentLarge || []).length === 0 ? (
            <EmptyState text="当前没有大额流水" />
          ) : (
            (stats?.recentLarge || []).map((item) => (
              <View
                key={item._id}
                className={styles.largeItem}
                onClick={() => openStatement(item.statementId)}
              >
                <View className={styles.largeLeft}>
                  <View className={styles.largeNameRow}>
                    <Text className={styles.largeName}>{item.counterpartyName || '未知交易'}</Text>
                    <RiskTags tags={counterpartyTags.tagsFor(item.counterpartyName)} />
                  </View>
                  <Text className={styles.largeMeta}>
                    {item.transDate} · {item.summary || '--'}
                  </Text>
                </View>
                <Text
                  className={item.amount >= 0 ? styles.largeIncome : styles.largeExpense}
                >
                  {formatSignedMoney(item.amount)}
                </Text>
              </View>
            ))
          )}
        </View>

        {loading ? <Text className={styles.loading}>数据加载中...</Text> : null}
      </View>
    </View>
  );
};

export default HomePage;
