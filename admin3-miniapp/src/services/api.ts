import Taro from '@tarojs/taro';
import { callFunction } from './cloud';
import { clearToken, getToken, request, setToken } from '@/utils/request';
import { API_BASE } from '@/config';
import type {
  AccountHolderOption,
  CounterpartyAnalysis,
  DashboardStats,
  HolderAnalysis,
  PageResult,
  RiskTagItem,
  Statement,
  StatementImportResult,
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

interface RawHolderOption {
  accountHolder: string;
  statementCount: number;
  accountCount: number;
}

interface RawAnalysisAccount {
  id: number;
  bankName: string;
  bankCode: string;
  accountNo: string;
  startDate: string | null;
  endDate: string | null;
  transactionCount: number;
  totalIncome: number;
  totalExpense: number;
  overview: RawBalanceOverview | null;
}

interface RawMaxBalanceTxn {
  transactionId: number | null;
  transDate: string | null;
  amount: number;
  direction: string | null;
  counterpartyName: string | null;
  summary: string | null;
  remark: string | null;
}

interface RawInterestItem {
  transactionId: number;
  transDate: string | null;
  amount: number;
  direction: string | null;
  counterpartyName: string | null;
  summary: string | null;
}

interface RawBalanceOverview {
  balanceAvailable: boolean;
  rangeBasis: string | null;
  statStart: string | null;
  statEnd: string | null;
  statDays: number;
  openingBalance: number | null;
  closingBalance: number | null;
  avgDailyBalance: number | null;
  maxBalance: number | null;
  maxBalanceDate: string | null;
  maxBalanceSource: string | null;
  maxBalanceTxn: RawMaxBalanceTxn | null;
  note: string | null;
  interestCount: number;
  interestTotal: number;
  interests: RawInterestItem[];
  interestExpenseCount: number;
  interestExpenseTotal: number;
  interestExpenses: RawInterestItem[];
}

interface RawCounterpartyRow {
  name: string;
  displayName: string;
  incomeCount: number;
  incomeTotal: number;
  expenseCount: number;
  expenseTotal: number;
  netAmount: number;
  totalFlow: number;
  lastDate: string | null;
  accountCount: number;
  holderCount: number;
  transactionCount: number;
  selfTransferCount: number;
  selfTransferAmount: number;
}

interface RawCounterpartyAccountOption {
  accountNo: string;
  bankName: string;
  accountHolder: string;
}

interface RawCounterpartyAnalysis {
  counterpartyCount: number;
  dualCount: number;
  transactionCount: number;
  excludedBlankCount: number;
  totalIncome: number;
  totalExpense: number;
  selfTransferCount: number;
  selfTransferAmount: number;
  holders: string[];
  accounts: RawCounterpartyAccountOption[];
  items: RawCounterpartyRow[];
}

interface RawMonthlyFlow {
  month: string;
  income: number;
  expense: number;
  net: number;
  count: number;
}

interface RawHolderAnalysis {
  accountHolder: string;
  statementCount: number;
  accountCount: number;
  transactionCount: number;
  incomeCount: number;
  expenseCount: number;
  totalIncome: number;
  totalExpense: number;
  netAmount: number;
  startDate: string | null;
  endDate: string | null;
  accounts: RawAnalysisAccount[];
  monthly: RawMonthlyFlow[];
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

/**
 * 按名称批量取风险标签：一次传当前页出现的名称，返回归一化名称 -> 标签数组。
 * 后端已做名称归一化（去空白+小写），与 Web 端共用同一份标签数据。
 */
export async function getRiskTags(
  subjectType: 'CUSTOMER' | 'COUNTERPARTY',
  names: string[]
): Promise<Record<string, RiskTagItem[]>> {
  if (!isWeapp) {
    return {};
  }
  return request<Record<string, RiskTagItem[]>>('/risk/tags/batch', {
    method: 'POST',
    data: { subjectType, names }
  });
}

const toNumber = (value: number | null | undefined): number | null =>
  value === null || value === undefined ? null : Number(value);

const toInterestItem = (raw: RawInterestItem) => ({
  transactionId: raw.transactionId,
  transDate: raw.transDate || null,
  amount: Number(raw.amount) || 0,
  direction: raw.direction || null,
  counterpartyName: raw.counterpartyName || null,
  summary: raw.summary || null
});

const toOverview = (raw: RawBalanceOverview | null) => {
  if (!raw) {
    return null;
  }
  return {
    balanceAvailable: Boolean(raw.balanceAvailable),
    rangeBasis: raw.rangeBasis || null,
    statStart: raw.statStart || null,
    statEnd: raw.statEnd || null,
    statDays: Number(raw.statDays) || 0,
    openingBalance: toNumber(raw.openingBalance),
    closingBalance: toNumber(raw.closingBalance),
    avgDailyBalance: toNumber(raw.avgDailyBalance),
    maxBalance: toNumber(raw.maxBalance),
    maxBalanceDate: raw.maxBalanceDate || null,
    maxBalanceSource: raw.maxBalanceSource || null,
    maxBalanceTxn: raw.maxBalanceTxn
      ? {
          transactionId: raw.maxBalanceTxn.transactionId,
          transDate: raw.maxBalanceTxn.transDate || null,
          amount: Number(raw.maxBalanceTxn.amount) || 0,
          direction: raw.maxBalanceTxn.direction || null,
          counterpartyName: raw.maxBalanceTxn.counterpartyName || null,
          summary: raw.maxBalanceTxn.summary || null,
          remark: raw.maxBalanceTxn.remark || null
        }
      : null,
    note: raw.note || null,
    interestCount: Number(raw.interestCount) || 0,
    interestTotal: Number(raw.interestTotal) || 0,
    interests: (raw.interests || []).map(toInterestItem),
    interestExpenseCount: Number(raw.interestExpenseCount) || 0,
    interestExpenseTotal: Number(raw.interestExpenseTotal) || 0,
    interestExpenses: (raw.interestExpenses || []).map(toInterestItem)
  };
};

/** 流水单分析：可选户名列表（含账户数、流水单数） */
export async function getAccountHolders(): Promise<AccountHolderOption[]> {
  if (!isWeapp) {
    return callFunction<AccountHolderOption[]>('getAccountHolders');
  }
  const raw = await request<RawHolderOption[]>('/bank-statements/account-holders');
  return (raw || []).map((item) => ({
    accountHolder: item.accountHolder,
    statementCount: Number(item.statementCount) || 0,
    accountCount: Number(item.accountCount) || 0
  }));
}

/** 流水单分析：按户名汇总所有账户总入账/总出款，以及逐月收支 */
export async function getHolderAnalysis(accountHolder: string): Promise<HolderAnalysis> {
  if (!isWeapp) {
    return callFunction<HolderAnalysis>('getHolderAnalysis', { accountHolder });
  }
  const raw = await request<RawHolderAnalysis>('/bank-statements/analysis', {
    data: { accountHolder }
  });
  return {
    accountHolder: raw.accountHolder,
    statementCount: Number(raw.statementCount) || 0,
    accountCount: Number(raw.accountCount) || 0,
    transactionCount: Number(raw.transactionCount) || 0,
    incomeCount: Number(raw.incomeCount) || 0,
    expenseCount: Number(raw.expenseCount) || 0,
    totalIncome: Number(raw.totalIncome) || 0,
    totalExpense: Number(raw.totalExpense) || 0,
    netAmount: Number(raw.netAmount) || 0,
    startDate: raw.startDate || null,
    endDate: raw.endDate || null,
    accounts: (raw.accounts || []).map((item) => ({
      id: item.id,
      bankName: item.bankName,
      bankCode: item.bankCode,
      accountNo: item.accountNo,
      startDate: item.startDate || null,
      endDate: item.endDate || null,
      transactionCount: Number(item.transactionCount) || 0,
      totalIncome: Number(item.totalIncome) || 0,
      totalExpense: Number(item.totalExpense) || 0,
      overview: toOverview(item.overview)
    })),
    monthly: (raw.monthly || []).map((item) => ({
      month: item.month,
      income: Number(item.income) || 0,
      expense: Number(item.expense) || 0,
      net: Number(item.net) || 0,
      count: Number(item.count) || 0
    }))
  };
}

/** 往来对手分析：按对方户名归并，仅返回同时存在转入与转出的对手 */
export async function getCounterpartyAnalysis(
  accountHolder?: string
): Promise<CounterpartyAnalysis> {
  if (!isWeapp) {
    return callFunction<CounterpartyAnalysis>('getCounterpartyAnalysis', { accountHolder });
  }
  const raw = await request<RawCounterpartyAnalysis>('/bank-statements/counterparties', {
    data: { accountHolder }
  });
  return {
    counterpartyCount: Number(raw.counterpartyCount) || 0,
    dualCount: Number(raw.dualCount) || 0,
    transactionCount: Number(raw.transactionCount) || 0,
    excludedBlankCount: Number(raw.excludedBlankCount) || 0,
    totalIncome: Number(raw.totalIncome) || 0,
    totalExpense: Number(raw.totalExpense) || 0,
    selfTransferCount: Number(raw.selfTransferCount) || 0,
    selfTransferAmount: Number(raw.selfTransferAmount) || 0,
    holders: raw.holders || [],
    accounts: (raw.accounts || []).map((item) => ({
      accountNo: item.accountNo,
      bankName: item.bankName,
      accountHolder: item.accountHolder
    })),
    items: (raw.items || []).map((item) => ({
      name: item.name,
      displayName: item.displayName,
      incomeCount: Number(item.incomeCount) || 0,
      incomeTotal: Number(item.incomeTotal) || 0,
      expenseCount: Number(item.expenseCount) || 0,
      expenseTotal: Number(item.expenseTotal) || 0,
      netAmount: Number(item.netAmount) || 0,
      totalFlow: Number(item.totalFlow) || 0,
      lastDate: item.lastDate || null,
      accountCount: Number(item.accountCount) || 0,
      holderCount: Number(item.holderCount) || 0,
      transactionCount: Number(item.transactionCount) || 0,
      selfTransferCount: Number(item.selfTransferCount) || 0,
      selfTransferAmount: Number(item.selfTransferAmount) || 0
    }))
  };
}

/**
 * 上传流水文件（PDF / 图片）并解析入库。
 *
 * <p>微信端通过 uploadFile 直传后端 /bank-statements/import，银行默认自动识别；
 * OCR 解析可能较久，超时放宽到 10 分钟。
 */
export async function uploadStatementPdf(
  filePath: string,
  fileName: string,
  bankCode?: string
): Promise<StatementImportResult> {
  const token = getToken();
  const result = await Taro.uploadFile({
    url: `${API_BASE}/bank-statements/import`,
    filePath,
    name: 'file',
    fileName,
    timeout: 600000,
    formData: bankCode ? { bankCode } : {},
    header: token ? { Authorization: `Bearer ${token}` } : {}
  });
  if (result.statusCode === 401) {
    clearToken();
    throw new Error('登录已过期，请重新登录');
  }
  if (result.statusCode >= 400) {
    let message = `上传失败（${result.statusCode}）`;
    try {
      const body = JSON.parse(result.data) as { message?: string };
      if (body?.message) {
        message = body.message;
      }
    } catch (err) {
      // 错误体不是 JSON 时保留默认提示
    }
    throw new Error(message);
  }
  try {
    return JSON.parse(result.data) as StatementImportResult;
  } catch (err) {
    throw new Error('解析返回结果失败，请重试');
  }
}
