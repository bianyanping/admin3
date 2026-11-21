import React, { useCallback, useEffect, useState } from 'react';
import { Picker, Text, View } from '@tarojs/components';
import Taro, { usePullDownRefresh } from '@tarojs/taro';
import styles from './index.module.scss';
import EmptyState from '@/components/EmptyState';
import { getAccountHolders, getCounterpartyAnalysis, getHolderAnalysis } from '@/services/api';
import type { AccountHolderOption, CounterpartyAnalysis, HolderAnalysis } from '@/types';
import { formatMoney, formatShortMoney, formatSignedMoney, maskAccount } from '@/utils/format';

/** 往来对手默认展示条数，超出后折叠 */
const CP_PREVIEW_SIZE = 8;

const moneyOrDash = (value?: number | null): string =>
  value === null || value === undefined ? '--' : formatMoney(value);

/**
 * 流水单分析：按户名汇总所有账户的总入账/总出款、逐月收支、账户概览（含余额明细）与往来对手。
 * 数据口径与 Web 端「流水单分析」一致，共用 /bank-statements 下的分析接口。
 */
const AnalysisPage: React.FC = () => {
  const [holders, setHolders] = useState<AccountHolderOption[]>([]);
  const [holderIndex, setHolderIndex] = useState(0);
  const [analysis, setAnalysis] = useState<HolderAnalysis | null>(null);
  const [counterparties, setCounterparties] = useState<CounterpartyAnalysis | null>(null);
  const [cpLoading, setCpLoading] = useState(false);
  const [expandedAccount, setExpandedAccount] = useState('');
  const [showAllCp, setShowAllCp] = useState(false);
  const [loading, setLoading] = useState(false);

  const currentHolder = holders[holderIndex]?.accountHolder || '';

  const loadCounterparties = useCallback(async (holder: string) => {
    if (!holder) {
      setCounterparties(null);
      return;
    }
    setCpLoading(true);
    try {
      const data = await getCounterpartyAnalysis(holder);
      setCounterparties(data);
    } catch (err) {
      // 往来对手依赖分析权限，失败时不阻塞主分析内容
      console.error('[Analysis] 往来对手加载失败', err);
      setCounterparties(null);
    } finally {
      setCpLoading(false);
    }
  }, []);

  const loadAnalysis = useCallback(
    async (holder: string) => {
      if (!holder) {
        setAnalysis(null);
        setCounterparties(null);
        return;
      }
      setLoading(true);
      setExpandedAccount('');
      setShowAllCp(false);
      try {
        const data = await getHolderAnalysis(holder);
        setAnalysis(data);
        console.info('[Analysis] 户名分析加载完成', {
          holder,
          accounts: data?.accounts.length || 0,
          months: data?.monthly.length || 0
        });
      } catch (err) {
        console.error('[Analysis] 户名分析加载失败', err);
        setAnalysis(null);
        Taro.showToast({ title: (err as Error).message || '加载失败', icon: 'none' });
      } finally {
        setLoading(false);
      }
      loadCounterparties(holder);
    },
    [loadCounterparties]
  );

  const loadHolders = useCallback(
    async (preferred?: string) => {
      setLoading(true);
      try {
        const list = await getAccountHolders();
        setHolders(list);
        if (list.length === 0) {
          setAnalysis(null);
          setCounterparties(null);
          return;
        }
        const index = preferred
          ? Math.max(list.findIndex((item) => item.accountHolder === preferred), 0)
          : 0;
        setHolderIndex(index);
        await loadAnalysis(list[index].accountHolder);
      } catch (err) {
        console.error('[Analysis] 户名列表加载失败', err);
        Taro.showToast({ title: (err as Error).message || '加载失败', icon: 'none' });
      } finally {
        setLoading(false);
      }
    },
    [loadAnalysis]
  );

  useEffect(() => {
    loadHolders();
  }, [loadHolders]);

  usePullDownRefresh(async () => {
    await loadHolders(currentHolder);
    Taro.stopPullDownRefresh();
  });

  const handleHolderChange = (index: number) => {
    setHolderIndex(index);
    loadAnalysis(holders[index]?.accountHolder || '');
  };

  const toggleAccount = (key: string) => {
    setExpandedAccount((prev) => (prev === key ? '' : key));
  };

  const renderAccountExpand = (key: string) => {
    const account = analysis?.accounts.find(
      (item) => `${item.bankCode}-${item.accountNo}` === key
    );
    const overview = account?.overview;
    const interestTotal =
      (overview?.interests.length || 0) + (overview?.interestExpenses.length || 0);

    return (
      <View className={styles.accountExpand}>
        <View className={styles.expandBlock}>
          <Text className={styles.expandTitle}>计算口径</Text>
          <Text className={styles.expandText}>{overview?.rangeBasis || '无余额数据'}</Text>
          {overview?.balanceAvailable ? (
            <Text className={styles.expandText}>
              统计区间 {overview.statStart || '--'} ~ {overview.statEnd || '--'}，共{' '}
              {overview.statDays} 天
            </Text>
          ) : null}
          {overview?.note ? <Text className={styles.expandText}>{overview.note}</Text> : null}
        </View>

        {overview?.balanceAvailable && overview.maxBalanceTxn ? (
          <View className={styles.expandBlock}>
            <Text className={styles.expandTitle}>最高余额对应交易</Text>
            <Text className={styles.expandText}>
              {overview.maxBalanceTxn.transDate || '--'} ·{' '}
              {overview.maxBalanceTxn.counterpartyName || '--'}
            </Text>
            <Text className={styles.expandText}>
              {overview.maxBalanceTxn.direction === '入' ? '入账 ' : '出款 '}
              {formatMoney(overview.maxBalanceTxn.amount)}
            </Text>
            <Text className={styles.expandText}>{overview.maxBalanceTxn.summary || '--'}</Text>
            {overview.maxBalanceTxn.remark ? (
              <Text className={styles.expandText}>备注：{overview.maxBalanceTxn.remark}</Text>
            ) : null}
          </View>
        ) : null}

        <View className={styles.expandBlock}>
          <Text className={styles.expandTitle}>结息明细</Text>
          {overview ? (
            <Text className={styles.expandText}>
              收入 {overview.interestCount} 笔 / {formatMoney(overview.interestTotal)}
              {overview.interestExpenseCount > 0
                ? ` · 支出 ${overview.interestExpenseCount} 笔 / ${formatMoney(
                    overview.interestExpenseTotal
                  )}`
                : ''}
            </Text>
          ) : null}
          {interestTotal === 0 ? (
            <Text className={styles.expandText}>无结息</Text>
          ) : (
            <>
              {(overview?.interests || []).map((item) => (
                <View key={`in-${item.transactionId}`} className={styles.interestItem}>
                  <Text className={styles.interestDate}>{item.transDate || '--'}</Text>
                  <Text className={styles.interestName}>
                    {item.counterpartyName || '--'} · {item.summary || '--'}
                  </Text>
                  <Text className={styles.interestAmountIncome}>+{formatMoney(item.amount)}</Text>
                </View>
              ))}
              {(overview?.interestExpenses || []).map((item) => (
                <View key={`out-${item.transactionId}`} className={styles.interestItem}>
                  <Text className={styles.interestDate}>{item.transDate || '--'}</Text>
                  <Text className={styles.interestName}>
                    {item.counterpartyName || '--'} · {item.summary || '--'}
                  </Text>
                  <Text className={styles.interestAmountExpense}>
                    -{formatMoney(item.amount)}
                  </Text>
                </View>
              ))}
            </>
          )}
        </View>
      </View>
    );
  };

  const renderCounterparties = () => {
    if (!counterparties) {
      return (
        <Text className={styles.cpEmpty}>{cpLoading ? '加载中...' : '暂无往来对手数据'}</Text>
      );
    }
    const items = showAllCp
      ? counterparties.items
      : counterparties.items.slice(0, CP_PREVIEW_SIZE);
    return (
      <>
        <View className={styles.cpStats}>
          <View className={styles.cpStat}>
            <Text className={styles.cellLabel}>转入</Text>
            <Text className={styles.cellIncome}>{formatMoney(counterparties.totalIncome)}</Text>
          </View>
          <View className={styles.cpStat}>
            <Text className={styles.cellLabel}>转出</Text>
            <Text className={styles.cellExpense}>{formatMoney(counterparties.totalExpense)}</Text>
          </View>
          <View className={styles.cpStat}>
            <Text className={styles.cellLabel}>自有账户互转</Text>
            <Text className={styles.cpValue}>
              {counterparties.selfTransferCount} 笔 /{' '}
              {formatShortMoney(counterparties.selfTransferAmount)}
            </Text>
          </View>
        </View>
        <Text className={styles.cpMeta}>
          空户名排除 {counterparties.excludedBlankCount} 笔 · 仅列出同时有转入和转出的对手
        </Text>

        {counterparties.items.length === 0 ? (
          <EmptyState text="暂无同时存在转入与转出的对手" />
        ) : (
          <>
            {items.map((row) => (
              <View key={row.name} className={styles.cpItem}>
                <View className={styles.cpHead}>
                  <Text className={styles.cpName}>{row.displayName}</Text>
                  {row.selfTransferCount > 0 ? (
                    <Text className={styles.cpTag}>自有互转</Text>
                  ) : null}
                </View>
                <View className={styles.cpFlow}>
                  <View className={styles.cpCell}>
                    <Text className={styles.cellLabel}>转入</Text>
                    <Text className={styles.cellIncome}>{formatMoney(row.incomeTotal)}</Text>
                    <Text className={styles.cpCount}>{row.incomeCount} 笔</Text>
                  </View>
                  <View className={styles.cpCell}>
                    <Text className={styles.cellLabel}>转出</Text>
                    <Text className={styles.cellExpense}>{formatMoney(row.expenseTotal)}</Text>
                    <Text className={styles.cpCount}>{row.expenseCount} 笔</Text>
                  </View>
                </View>
                <View className={styles.cpFoot}>
                  <Text className={styles.cpFootText}>
                    净额{' '}
                    <Text
                      className={row.netAmount >= 0 ? styles.cellIncome : styles.cellExpense}
                    >
                      {formatSignedMoney(row.netAmount)}
                    </Text>
                  </Text>
                  <Text className={styles.cpFootText}>最近 {row.lastDate || '--'}</Text>
                  <Text className={styles.cpFootText}>{row.accountCount} 个账户</Text>
                </View>
              </View>
            ))}
            {counterparties.items.length > CP_PREVIEW_SIZE ? (
              <View className={styles.cpToggle} onClick={() => setShowAllCp(!showAllCp)}>
                <Text className={styles.cpToggleText}>
                  {showAllCp ? '收起' : `查看全部 ${counterparties.items.length} 个`}
                </Text>
              </View>
            ) : null}
          </>
        )}
      </>
    );
  };

  return (
    <View className={styles.page}>
      <View className={styles.header}>
        <Text className={styles.headerTitle}>流水单分析</Text>
        <Text className={styles.headerSub}>按户名汇总所有账户的总入账、总出款与每月收支</Text>
        <Picker
          mode="selector"
          range={holders.map((item) => item.accountHolder)}
          value={holderIndex}
          onChange={(event) => handleHolderChange(Number(event.detail.value))}
        >
          <View className={styles.holderPicker}>
            <Text className={styles.holderName}>{currentHolder || '暂无可选户名'}</Text>
            <Text className={styles.holderArrow}>切换 ›</Text>
          </View>
        </Picker>
      </View>

      <View className={styles.body}>
        {analysis ? (
          <>
            <View className={styles.meta}>
              <Text className={styles.metaText}>
                {analysis.accountCount} 个账户 · {analysis.statementCount} 份流水单 ·{' '}
                {analysis.transactionCount} 笔
              </Text>
              <Text className={styles.metaText}>
                账期 {analysis.startDate || '--'} ~ {analysis.endDate || '--'}
              </Text>
            </View>

            <View className={styles.statRow}>
              <View className={styles.statCard}>
                <Text className={styles.statLabel}>总入账</Text>
                <Text className={styles.statIncome}>{formatMoney(analysis.totalIncome)}</Text>
                <Text className={styles.statSub}>共 {analysis.incomeCount} 笔</Text>
              </View>
              <View className={styles.statCard}>
                <Text className={styles.statLabel}>总出款</Text>
                <Text className={styles.statExpense}>{formatMoney(analysis.totalExpense)}</Text>
                <Text className={styles.statSub}>共 {analysis.expenseCount} 笔</Text>
              </View>
            </View>

            <View className={styles.netCard}>
              <Text className={styles.statLabel}>净额</Text>
              <Text className={analysis.netAmount >= 0 ? styles.statIncome : styles.statExpense}>
                {formatSignedMoney(analysis.netAmount)}
              </Text>
            </View>

            <View className={styles.card}>
              <View className={styles.cardHead}>
                <Text className={styles.cardTitle}>每月收支</Text>
                <Text className={styles.cardSub}>{analysis.monthly.length} 个月</Text>
              </View>
              {analysis.monthly.length === 0 ? (
                <EmptyState text="暂无月度收支数据" />
              ) : (
                analysis.monthly.map((item) => (
                  <View key={item.month} className={styles.monthItem}>
                    <View className={styles.monthHead}>
                      <Text className={styles.monthName}>{item.month}</Text>
                      <Text className={styles.monthCount}>{item.count} 笔</Text>
                    </View>
                    <View className={styles.monthGrid}>
                      <View className={styles.monthCell}>
                        <Text className={styles.cellLabel}>入账</Text>
                        <Text className={styles.cellIncome}>{formatMoney(item.income)}</Text>
                      </View>
                      <View className={styles.monthCell}>
                        <Text className={styles.cellLabel}>出款</Text>
                        <Text className={styles.cellExpense}>{formatMoney(item.expense)}</Text>
                      </View>
                    </View>
                    <View className={styles.monthNet}>
                      <Text className={styles.cellLabel}>净额</Text>
                      <Text className={item.net >= 0 ? styles.cellIncome : styles.cellExpense}>
                        {formatSignedMoney(item.net)}
                      </Text>
                    </View>
                  </View>
                ))
              )}
            </View>

            <View className={styles.card}>
              <View className={styles.cardHead}>
                <Text className={styles.cardTitle}>账户概览</Text>
                <Text className={styles.cardSub}>
                  {analysis.accounts.length} 个账户 · 日均余额 = Σ(日末余额 × 持续天数) / 统计天数
                </Text>
              </View>
              {analysis.accounts.length === 0 ? (
                <EmptyState text="暂无账户数据" />
              ) : (
                analysis.accounts.map((account) => {
                  const key = `${account.bankCode}-${account.accountNo}`;
                  const overview = account.overview;
                  const expanded = expandedAccount === key;
                  return (
                    <View key={key} className={styles.accountItem}>
                      <View className={styles.accountHead}>
                        <Text className={styles.accountBank}>{account.bankName}</Text>
                        <Text className={styles.accountNo}>{maskAccount(account.accountNo)}</Text>
                      </View>
                      <Text className={styles.accountMeta}>
                        {account.startDate || '--'} ~ {account.endDate || '--'} ·{' '}
                        {account.transactionCount} 笔
                      </Text>
                      <View className={styles.accountFlow}>
                        <Text className={styles.cellIncome}>
                          入 {formatShortMoney(account.totalIncome)}
                        </Text>
                        <Text className={styles.cellExpense}>
                          出 {formatShortMoney(account.totalExpense)}
                        </Text>
                      </View>

                      <View className={styles.balanceGrid}>
                        <View className={styles.balanceCell}>
                          <Text className={styles.cellLabel}>期初余额</Text>
                          <Text className={styles.balanceValue}>
                            {moneyOrDash(overview?.openingBalance)}
                          </Text>
                        </View>
                        <View className={styles.balanceCell}>
                          <Text className={styles.cellLabel}>期末余额</Text>
                          <Text className={styles.balanceValue}>
                            {moneyOrDash(overview?.closingBalance)}
                          </Text>
                        </View>
                        <View className={styles.balanceCell}>
                          <Text className={styles.cellLabel}>日均余额</Text>
                          <Text className={styles.balanceValue}>
                            {overview?.balanceAvailable
                              ? formatMoney(overview.avgDailyBalance)
                              : '--'}
                          </Text>
                        </View>
                        <View className={styles.balanceCell}>
                          <Text className={styles.cellLabel}>最高余额</Text>
                          <Text className={styles.balanceValue}>
                            {overview?.balanceAvailable ? formatMoney(overview.maxBalance) : '--'}
                          </Text>
                          {overview?.balanceAvailable ? (
                            <Text className={styles.cpCount}>{overview.maxBalanceDate || '--'}</Text>
                          ) : null}
                        </View>
                      </View>

                      <View className={styles.expandToggle} onClick={() => toggleAccount(key)}>
                        <Text className={styles.expandToggleText}>
                          {expanded ? '收起余额明细' : '余额明细'}
                        </Text>
                      </View>

                      {expanded ? renderAccountExpand(key) : null}
                    </View>
                  );
                })
              )}
            </View>

            <View className={styles.card}>
              <View className={styles.cardHead}>
                <Text className={styles.cardTitle}>往来对手</Text>
                <Text className={styles.cardSub}>
                  {counterparties
                    ? `对手 ${counterparties.counterpartyCount} 个 · 双向 ${counterparties.dualCount} 个`
                    : cpLoading
                      ? '统计中...'
                      : '--'}
                </Text>
              </View>
              {renderCounterparties()}
            </View>

            {loading ? <Text className={styles.loading}>加载中...</Text> : null}
          </>
        ) : (
          <View className={styles.placeholder}>
            {loading ? (
              <Text className={styles.loading}>加载中...</Text>
            ) : (
              <EmptyState
                text={holders.length > 0 ? '该户名暂无分析数据' : '暂无可分析的流水单'}
              />
            )}
          </View>
        )}
      </View>
    </View>
  );
};

export default AnalysisPage;
