import Taro from '@tarojs/taro';
import { callFunction } from './cloud';
import { request, setToken } from '@/utils/request';
import type {
  DashboardStats,
  PageResult,
  Statement,
  StatementQuery,
  Transaction,
  TransactionQuery,
  UserProfile
} from '@/types';

/**
 * 业务接口层
 *
 * 微信端直接调用后台 `/admin3` 接口，登录后使用后台会话 token，
 * 可见范围与 Web 端共用「组织架构 + 分享」权限；
 * 非微信端（H5 预览）回落到 src/data 下的 mock。
 */

const isWeapp = process.env.TARO_ENV === 'weapp';

/** 微信尚未绑定后台账号时抛出，登录页据此展示绑定表单 */
export class NeedBindError extends Error {
  readonly openid: string;

  constructor(openid: string) {
    super('该微信尚未绑定后台账号');
    this.openid = openid;
  }
}

interface RawStatement {
  id: number;
  bankCode: string;
  bankName: string;
  accountHolder: string;
  accountNo: string;
  startDate: string;
  endDate: string;
  currency: string;
  transactionCount: number;
  totalIncome: number;
  totalExpense: number;
  createTime: string;
}

interface RawTransaction {
  id: number;
  seqNo: number;
  transDate: string;
  transTime?: string;
  amount: number;
  balance?: number;
  direction?: string;
  counterpartyName?: string;
  counterpartyAccount?: string;
  summary?: string;
  remark?: string;
}

interface RawLargeItem {
  id: number;
  statementId: number;
  transDate: string;
  amount: number;
  counterpartyName?: string;
  summary?: string;
}

interface RawDashboard {
  totalCount: number;
  totalIncome: number;
  totalExpense: number;
  todayCount: number;
  anomalyCount: number;
  daily: { date: string; income: number; expense: number }[];
  categories: { name: string; amount: number; percent: number }[];
  topCounterparties: { name: string; amount: number }[];
  recentLarge: RawLargeItem[];
}

interface PageResponse<T> {
  content: T[];
  totalElements: number;
}

interface RawMiniUser {
  userId: number;
  username: string;
  nickname: string;
  avatar: string;
  orgName: string | null;
  permissions: string[];
  lastLoginTime: string;
}

interface RawLoginResult {
  bound: boolean;
  openid: string;
  token: string | null;
  user: RawMiniUser | null;
}

const toStatement = (raw: RawStatement): Statement => ({
  _id: String(raw.id),
  bankCode: raw.bankCode,
  bankName: raw.bankName,
  accountHolder: raw.accountHolder,
  accountNo: raw.accountNo,
  startDate: raw.startDate,
  endDate: raw.endDate,
  currency: raw.currency,
  transactionCount: raw.transactionCount,
  totalIncome: Number(raw.totalIncome),
  totalExpense: Number(raw.totalExpense),
  createTime: raw.createTime
});

const toTransaction = (raw: RawTransaction, statementId: string): Transaction => ({
  _id: String(raw.id),
  statementId,
  seqNo: raw.seqNo,
  transDate: raw.transDate,
  transTime: raw.transTime,
  amount: Number(raw.amount),
  balance: raw.balance === undefined ? undefined : Number(raw.balance),
  direction: raw.direction || (Number(raw.amount) >= 0 ? '入' : '出'),
  counterpartyName: raw.counterpartyName,
  counterpartyAccount: raw.counterpartyAccount,
  summary: raw.summary,
  remark: raw.remark
});

const toProfile = (raw: RawMiniUser): UserProfile => ({
  userId: raw.userId,
  username: raw.username,
  nickname: raw.nickname || raw.username,
  avatar: raw.avatar || '',
  orgName: raw.orgName || '',
  permissions: raw.permissions || [],
  lastLoginTime: raw.lastLoginTime
});

/** 微信登录：已绑定返回用户信息，未绑定抛 NeedBindError */
export async function login(): Promise<{ user: UserProfile }> {
  if (!isWeapp) {
    return callFunction<{ user: UserProfile }>('login');
  }
  const { code } = await Taro.login();
  const result = await request<RawLoginResult>('/api/miniapp/login', {
    method: 'POST',
    data: { code }
  });
  if (!result.bound || !result.token || !result.user) {
    throw new NeedBindError(result.openid);
  }
  setToken(result.token);
  return { user: toProfile(result.user) };
}

/** 首次使用：用后台账号密码绑定当前微信 */
export async function bindAccount(
  username: string,
  password: string
): Promise<{ user: UserProfile }> {
  if (!isWeapp) {
    return callFunction<{ user: UserProfile }>('login');
  }
  const { code } = await Taro.login();
  const result = await request<RawLoginResult>('/api/miniapp/bind', {
    method: 'POST',
    data: { code, username, password }
  });
  if (!result.token || !result.user) {
    throw new Error('绑定失败，请重试');
  }
  setToken(result.token);
  return { user: toProfile(result.user) };
}

export async function getDashboard(days: number): Promise<DashboardStats> {
  if (!isWeapp) {
    return callFunction<DashboardStats>('getDashboard', { days });
  }
  const raw = await request<RawDashboard>('/bank-statements/dashboard', {
    data: { days }
  });
  return {
    totalCount: raw.totalCount,
    totalIncome: Number(raw.totalIncome),
    totalExpense: Number(raw.totalExpense),
    todayCount: raw.todayCount,
    largeCount: raw.anomalyCount,
    daily: (raw.daily || []).map((item) => ({
      date: item.date,
      income: Number(item.income),
      expense: Number(item.expense)
    })),
    categories: (raw.categories || []).map((item) => ({
      name: item.name,
      amount: Number(item.amount),
      percent: Number(item.percent)
    })),
    topCounterparties: (raw.topCounterparties || []).map((item) => ({
      name: item.name,
      amount: Number(item.amount)
    })),
    recentLarge: (raw.recentLarge || []).map((item) => ({
      _id: String(item.id),
      statementId: String(item.statementId),
      seqNo: 0,
      transDate: item.transDate,
      amount: Number(item.amount),
      direction: Number(item.amount) >= 0 ? '入' : '出',
      counterpartyName: item.counterpartyName,
      summary: item.summary
    }))
  };
}

export async function getStatements(
  query: StatementQuery = {}
): Promise<PageResult<Statement>> {
  if (!isWeapp) {
    return callFunction<PageResult<Statement>>('getStatements', query);
  }
  const raw = await request<PageResponse<RawStatement>>('/bank-statements', {
    data: {
      page: query.page || 1,
      size: query.size || 10,
      keyword: query.keyword,
      bankCode: query.bankCode
    }
  });
  return {
    list: (raw.content || []).map(toStatement),
    total: raw.totalElements
  };
}

export async function getStatementDetail(id: string): Promise<Statement> {
  if (!isWeapp) {
    return callFunction<Statement>('getStatementDetail', { id });
  }
  const raw = await request<RawStatement>(`/bank-statements/${id}`);
  return toStatement(raw);
}

export async function getTransactions(
  query: TransactionQuery
): Promise<PageResult<Transaction>> {
  if (!isWeapp) {
    return callFunction<PageResult<Transaction>>('getTransactions', query);
  }
  const raw = await request<PageResponse<RawTransaction>>(
    `/bank-statements/${query.statementId}/transactions`,
    {
      data: {
        page: query.page || 1,
        size: query.size || 20,
        direction: query.direction,
        keyword: query.keyword
      }
    }
  );
  return {
    list: (raw.content || []).map((item) => toTransaction(item, query.statementId)),
    total: raw.totalElements
  };
}
