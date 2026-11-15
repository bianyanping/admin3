/**
 * 全局业务类型定义
 */

/** 登录用户 */
export interface UserProfile {
  userId?: number;
  username?: string;
  openid?: string;
  nickname: string;
  avatar: string;
  /** 所属组织全路径，如「总部-财务部-会计组」，决定可见数据范围 */
  orgName?: string;
  permissions?: string[];
  lastLoginTime?: string;
}

/** 流水单（一份对账单） */
export interface Statement {
  _id: string;
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

/** 交易明细 */
export interface Transaction {
  _id: string;
  statementId: string;
  seqNo: number;
  transDate: string;
  transTime?: string;
  amount: number;
  balance?: number;
  direction: string;
  counterpartyName?: string;
  counterpartyAccount?: string;
  summary?: string;
  remark?: string;
}

/** 每日收支 */
export interface DailyPoint {
  date: string;
  income: number;
  expense: number;
}

/** 流水构成 */
export interface CategoryStat {
  name: string;
  amount: number;
  percent: number;
}

/** 交易对手 */
export interface CounterpartyStat {
  name: string;
  amount: number;
}

/** 首页看板数据 */
export interface DashboardStats {
  totalCount: number;
  totalIncome: number;
  totalExpense: number;
  todayCount: number;
  largeCount: number;
  daily: DailyPoint[];
  categories: CategoryStat[];
  topCounterparties: CounterpartyStat[];
  recentLarge: Transaction[];
}

/** 通用分页结果 */
export interface PageResult<T> {
  list: T[];
  total: number;
}

/** 流水单查询参数 */
export interface StatementQuery {
  keyword?: string;
  bankCode?: string;
  page?: number;
  size?: number;
}

/** 明细查询参数 */
export interface TransactionQuery {
  statementId: string;
  direction?: string;
  keyword?: string;
  page?: number;
  size?: number;
}
