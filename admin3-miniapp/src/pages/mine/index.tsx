import React, { useState } from 'react';
import { Image, Text, View } from '@tarojs/components';
import Taro, { useDidShow } from '@tarojs/taro';
import styles from './index.module.scss';
import { getDashboard, getStatements, login } from '@/services/api';
import { useAuthStore } from '@/store/auth';
import { formatMoney, formatShortMoney } from '@/utils/format';

const MinePage: React.FC = () => {
  const profile = useAuthStore((state) => state.profile);
  const setProfile = useAuthStore((state) => state.setProfile);
  const clear = useAuthStore((state) => state.clear);

  const [statementTotal, setStatementTotal] = useState(0);
  const [totalCount, setTotalCount] = useState(0);
  const [totalAmount, setTotalAmount] = useState(0);

  const loadOverview = async () => {
    try {
      const [statements, dashboard] = await Promise.all([
        getStatements({ page: 1, size: 1 }),
        getDashboard(30)
      ]);
      setStatementTotal(statements.total);
      setTotalCount(dashboard.totalCount);
      setTotalAmount(Number(dashboard.totalIncome) + Number(dashboard.totalExpense));
    } catch (err) {
      console.error('[Mine] 概览数据加载失败', err);
    }
  };

  useDidShow(() => {
    loadOverview();
  });

  const handleRelogin = async () => {
    try {
      const result = await login();
      setProfile(result.user);
      Taro.showToast({ title: '已重新登录', icon: 'success' });
    } catch (err) {
      console.error('[Mine] 重新登录失败', err);
      Taro.showToast({ title: '登录失败', icon: 'none' });
    }
  };

  const handleClearCache = async () => {
    const res = await Taro.showModal({
      title: '清除缓存',
      content: '将清除本地缓存的登录信息，确定继续？',
      confirmColor: '#f53f3f'
    });
    if (!res.confirm) return;
    clear();
    Taro.showToast({ title: '已清除缓存', icon: 'success' });
    setTimeout(() => {
      Taro.reLaunch({ url: '/pages/login/index' });
    }, 600);
  };

  const handleAbout = () => {
    Taro.showModal({
      title: '关于',
      content: '账单流水分析系统小程序\n版本 1.0.0\n支持流水单查询、收支分析与大额流水监控。',
      showCancel: false,
      confirmColor: '#165dff'
    });
  };

  return (
    <View className={styles.page}>
      {/* 用户卡片 */}
      <View className={styles.profileCard}>
        <Image className={styles.avatar} src={profile?.avatar || ''} mode="aspectFill" />
        <View className={styles.profileInfo}>
          <Text className={styles.nickname}>{profile?.nickname || '未登录'}</Text>
          <Text className={styles.identity}>
            {profile?.orgName ? `${profile.orgName} · 部门权限` : '未绑定组织'}
          </Text>
        </View>
      </View>

      {/* 数据概览 */}
      <View className={styles.overviewCard}>
        <View className={styles.overviewItem}>
          <Text className={styles.overviewValue}>{statementTotal}</Text>
          <Text className={styles.overviewLabel}>流水单</Text>
        </View>
        <View className={styles.overviewLine} />
        <View className={styles.overviewItem}>
          <Text className={styles.overviewValue}>{totalCount}</Text>
          <Text className={styles.overviewLabel}>交易笔数</Text>
        </View>
        <View className={styles.overviewLine} />
        <View className={styles.overviewItem}>
          <Text className={styles.overviewValue}>{formatShortMoney(totalAmount)}</Text>
          <Text className={styles.overviewLabel}>累计金额</Text>
        </View>
      </View>

      {/* 功能列表 */}
      <View className={styles.menuCard}>
        <View className={styles.menuItem} onClick={handleRelogin}>
          <Text className={styles.menuText}>重新登录</Text>
          <Text className={styles.menuArrow}>›</Text>
        </View>
        <View className={styles.menuItem} onClick={handleClearCache}>
          <Text className={styles.menuText}>清除本地缓存</Text>
          <Text className={styles.menuArrow}>›</Text>
        </View>
        <View className={styles.menuItem} onClick={handleAbout}>
          <Text className={styles.menuText}>关于系统</Text>
          <Text className={styles.menuArrow}>›</Text>
        </View>
      </View>

      <View className={styles.tipCard}>
        <Text className={styles.tipTitle}>数据说明</Text>
        <Text className={styles.tipText}>
          仅展示你所在组织权限范围内的流水数据，单笔金额 ≥ 5 万的交易会被统计为异常笔数。
        </Text>
        <Text className={styles.tipText}>
          最近一次登录：{profile?.lastLoginTime || '--'}
        </Text>
        <Text className={styles.tipText}>累计金额：{formatMoney(totalAmount)}</Text>
      </View>
    </View>
  );
};

export default MinePage;
