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

/** 风险标签（手工/系统规则） */
export interface RiskTagItem {
  id: number;
  code: string;
  name: string;
  color?: string;
  subjectType: string;
  source: 'MANUAL' | 'SYSTEM';
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

/** 户名选项（流水单分析页筛选） */
export interface AccountHolderOption {
  accountHolder: string;
  statementCount: number;
  accountCount: number;
}

/** 最高余额对应的交易 */
export interface MaxBalanceTxn {
  transactionId: number | null;
  transDate: string | null;
  amount: number;
  direction: string | null;
  counterpartyName: string | null;
  summary: string | null;
  remark: string | null;
}

/** 结息交易 */
export interface InterestItem {
  transactionId: number;
  transDate: string | null;
  amount: number;
  direction: string | null;
  counterpartyName: string | null;
  summary: string | null;
}

/** 账户余额概览：日均、最高、期初期末与结息 */
export interface AccountBalanceOverview {
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
  maxBalanceTxn: MaxBalanceTxn | null;
  note: string | null;
  interestCount: number;
  interestTotal: number;
  interests: InterestItem[];
  interestExpenseCount: number;
  interestExpenseTotal: number;
  interestExpenses: InterestItem[];
}

/** 账户汇总（流水单分析页） */
export interface AnalysisAccount {
  id: number;
  bankName: string;
  bankCode: string;
  accountNo: string;
  startDate: string | null;
  endDate: string | null;
  transactionCount: number;
  totalIncome: number;
  totalExpense: number;
  overview: AccountBalanceOverview | null;
}

/** 月度收支 */
export interface MonthlyFlow {
  month: string;
  income: number;
  expense: number;
  net: number;
  count: number;
}

/** 按户名汇总：总入账/总出款 + 逐月收支 + 账户明细 */
export interface HolderAnalysis {
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
  accounts: AnalysisAccount[];
  monthly: MonthlyFlow[];
}

/** 往来对手一行 */
export interface CounterpartyRow {
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

/** 往来对手可筛选账户 */
export interface CounterpartyAccountOption {
  accountNo: string;
  bankName: string;
  accountHolder: string;
}

/** 往来对手分析结果 */
export interface CounterpartyAnalysis {
  counterpartyCount: number;
  dualCount: number;
  transactionCount: number;
  excludedBlankCount: number;
  totalIncome: number;
  totalExpense: number;
  selfTransferCount: number;
  selfTransferAmount: number;
  holders: string[];
  accounts: CounterpartyAccountOption[];
  items: CounterpartyRow[];
}

/** 流水 PDF 上传导入结果 */
export interface StatementImportResult {
  mode: string;
  bankCode: string | null;
  bankName: string | null;
  accountNo: string | null;
  accountHolder: string | null;
  startDate: string | null;
  endDate: string | null;
  statementId: number | null;
  transactionCount: number;
  totalIncome: number | null;
  incomeCount: number | null;
  totalExpense: number | null;
  expenseCount: number | null;
  duplicated: boolean;
  merged: boolean;
  warnings: string[];
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
