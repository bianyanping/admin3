import dayjs from 'dayjs';
import type { CategoryStat, CounterpartyStat, DailyPoint, DashboardStats, Transaction } from '@/types';
import { mockTransactions } from './mockBase';

interface DashboardParams {
  days?: number;
}

/** 生成近 N 天的收支序列（带轻微波动，视觉上更真实） */
const buildDaily = (days: number): DailyPoint[] => {
  const result: DailyPoint[] = [];
  const today = dayjs();
  for (let i = days - 1; i >= 0; i--) {
    const date = today.subtract(i, 'day');
    const seed = date.date() + date.month() * 7;
    const income = i % 5 === 0 ? 0 : 8000 + ((seed * 137) % 26000);
    const expense = 6000 + ((seed * 211) % 22000);
    result.push({
      date: date.format('YYYY-MM-DD'),
      income,
      expense
    });
  }
  return result;
};

export default function mockGetDashboard(params?: DashboardParams): DashboardStats {
  const days = params?.days && params.days > 0 ? params.days : 30;
  const daily = buildDaily(days);

  const categories: CategoryStat[] = [
    { name: '转账', amount: 1862400, percent: 46.4 },
    { name: '消费', amount: 1043800, percent: 26.0 },
    { name: '工资', amount: 620000, percent: 15.4 },
    { name: '手续费', amount: 138600, percent: 3.5 },
    { name: '利息', amount: 62400, percent: 1.6 },
    { name: '其他', amount: 286000, percent: 7.1 }
  ];

  const topCounterparties: CounterpartyStat[] = [
    { name: '财付通支付科技有限公司', amount: 486200 },
    { name: '支付宝（中国）网络技术有限公司', amount: 392800 },
    { name: '广西某某农业发展有限公司', amount: 268400 },
    { name: '城乡商城', amount: 196300 },
    { name: '广西某某建材有限公司', amount: 158600 },
    { name: '中国移动通信集团', amount: 92400 },
    { name: '浙江某某农业科技发展有限公司', amount: 78600 },
    { name: '国网广西电力有限公司', amount: 56400 },
    { name: '顺丰速运有限公司', amount: 32800 },
    { name: '中国石油天然气股份有限公司', amount: 21600 }
  ];

  const recentLarge: Transaction[] = mockTransactions
    .filter((item) => Math.abs(item.amount) >= 5000)
    .slice(0, 8);

  return {
    totalCount: 2249,
    totalIncome: 5664303.12,
    totalExpense: 5983172.66,
    todayCount: 26,
    largeCount: 184,
    daily,
    categories,
    topCounterparties,
    recentLarge
  };
}
