import { mockStatements, mockTransactions } from './mockBase';
import type { HolderAnalysis, MonthlyFlow } from '@/types';

const monthKey = (date?: string): string => (date && date.length >= 7 ? date.slice(0, 7) : '');

/**
 * H5 预览用的户名分析 mock：口径与后端 /bank-statements/analysis 保持一致
 * （入账取金额为正的交易，出款取金额为负的绝对值，按月汇总）
 */
const getHolderAnalysis = (params: { accountHolder?: string } = {}): HolderAnalysis | null => {
  const holder = (params.accountHolder || '').trim();
  const statements = mockStatements.filter(
    (item) => (item.accountHolder || '').trim() === holder
  );
  if (!holder || statements.length === 0) {
    return null;
  }

  const statementIds = new Set(statements.map((item) => item._id));
  const transactions = mockTransactions.filter((item) => statementIds.has(item.statementId));

  let totalIncome = 0;
  let totalExpense = 0;
  let incomeCount = 0;
  let expenseCount = 0;
  const monthly = new Map<string, MonthlyFlow>();

  if (transactions.length > 0) {
    transactions.forEach((item) => {
      const amount = Number(item.amount) || 0;
      if (amount >= 0) {
        totalIncome += amount;
        incomeCount += 1;
      } else {
        totalExpense += Math.abs(amount);
        expenseCount += 1;
      }
      const key = monthKey(item.transDate);
      if (!key) return;
      const flow = monthly.get(key) || { month: key, income: 0, expense: 0, net: 0, count: 0 };
      if (amount >= 0) {
        flow.income += amount;
      } else {
        flow.expense += Math.abs(amount);
      }
      flow.net = flow.income - flow.expense;
      flow.count += 1;
      monthly.set(key, flow);
    });
  } else {
    statements.forEach((item) => {
      totalIncome += Number(item.totalIncome) || 0;
      totalExpense += Number(item.totalExpense) || 0;
    });
  }

  const startDates = statements.map((item) => item.startDate).filter(Boolean).sort();
  const endDates = statements.map((item) => item.endDate).filter(Boolean).sort();

  return {
    accountHolder: holder,
    statementCount: statements.length,
    accountCount: new Set(statements.map((item) => item.accountNo)).size,
    transactionCount:
      transactions.length > 0
        ? transactions.length
        : statements.reduce((sum, item) => sum + (item.transactionCount || 0), 0),
    incomeCount,
    expenseCount,
    totalIncome,
    totalExpense,
    netAmount: totalIncome - totalExpense,
    startDate: startDates[0] || null,
    endDate: endDates[endDates.length - 1] || null,
    accounts: statements.map((item) => ({
      id: Number(item._id.replace(/\D/g, '')) || 0,
      bankName: item.bankName,
      bankCode: item.bankCode,
      accountNo: item.accountNo,
      startDate: item.startDate || null,
      endDate: item.endDate || null,
      transactionCount: item.transactionCount || 0,
      totalIncome: Number(item.totalIncome) || 0,
      totalExpense: Number(item.totalExpense) || 0,
      overview: null
    })),
    monthly: Array.from(monthly.values()).sort((a, b) => a.month.localeCompare(b.month))
  };
};

export default getHolderAnalysis;
