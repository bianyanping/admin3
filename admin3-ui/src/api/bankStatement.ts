import request from '../utils/request';
import {BASE_URI} from './base';

/**
 * 银行流水相关接口
 */

// 流水单分页列表
export function listStatements(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/bank-statements`,
    method: 'get',
    params
  });
}

// 流水单详情
export function getStatement(id: number) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}`,
    method: 'get'
  });
}

// 明细分页
export function listTransactions(id: number, params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}/transactions`,
    method: 'get',
    params
  });
}

// 首页看板统计
export function getDashboard(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/bank-statements/dashboard`,
    method: 'get',
    params
  });
}

// 可选户名列表（含账户数、流水单数）
export function listAccountHolders() {
  return request({
    url: `${BASE_URI}/bank-statements/account-holders`,
    method: 'get'
  });
}

// 按户名汇总：总入账/总出款 + 逐月收支
export function getHolderAnalysis(accountHolder: string) {
  return request({
    url: `${BASE_URI}/bank-statements/analysis`,
    method: 'get',
    params: {accountHolder},
    timeout: 60000
  });
}

// 往来对手分析：双向对手汇总（入账 + 出账），可按户名筛选
export function getCounterparties(accountHolder?: string) {
  return request({
    url: `${BASE_URI}/bank-statements/counterparties`,
    method: 'get',
    params: {accountHolder: accountHolder || undefined},
    timeout: 60000
  });
}

// 单个对手的逐笔明细，支持客户 / 账户 / 时间范围筛选
export function getCounterpartyDetail(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/bank-statements/counterparties/detail`,
    method: 'get',
    params,
    timeout: 60000
  });
}

// ==================== 关键词分组 ====================

// 关键词分组列表
export function listKeywordGroups() {
  return request({
    url: `${BASE_URI}/keyword-groups`,
    method: 'get'
  });
}

// 新增关键词分组
export function createKeywordGroup(data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/keyword-groups`,
    method: 'post',
    data
  });
}

// 修改关键词分组
export function updateKeywordGroup(id: number, data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/keyword-groups/${id}`,
    method: 'put',
    data
  });
}

// 启用/停用关键词分组
export function setKeywordGroupEnabled(id: number, enabled: boolean) {
  return request({
    url: `${BASE_URI}/keyword-groups/${id}/enabled`,
    method: 'put',
    params: {enabled}
  });
}

// 删除关键词分组
export function deleteKeywordGroup(id: number) {
  return request({
    url: `${BASE_URI}/keyword-groups/${id}`,
    method: 'delete'
  });
}

// 关键词命中分析（可按户名筛选）
export function getKeywordAnalysis(accountHolder?: string) {
  return request({
    url: `${BASE_URI}/keyword-groups/analysis`,
    method: 'get',
    params: {accountHolder: accountHolder || undefined},
    timeout: 60000
  });
}

// 手动新增流水单
export function createStatement(data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/bank-statements`,
    method: 'post',
    data
  });
}

// 修改流水单基本信息
export function updateStatement(id: number, data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}`,
    method: 'put',
    data
  });
}

// 删除流水单（含其明细）
export function deleteStatement(id: number) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}`,
    method: 'delete'
  });
}

// 上传 PDF 并解析入库（OCR 可能耗时较长，不设超时）
// silent=true 时不弹全局错误提示，由调用方统一汇总（批量上传用）
export function importStatement(formData: FormData, options?: { silent?: boolean }) {
  return request({
    url: `${BASE_URI}/bank-statements/import`,
    method: 'post',
    headers: {'Content-Type': 'multipart/form-data'},
    data: formData,
    timeout: 600000,
    silent: options?.silent
  } as any);
}

// 下载流水导入 Excel 模板（需要鉴权，用 blob）
export function downloadImportTemplate() {
  return request({
    url: `${BASE_URI}/bank-statements/import-template`,
    method: 'get',
    responseType: 'blob',
    timeout: 60000
  });
}

// 下载原始 PDF 文件（需要鉴权，用 blob）
export function downloadSourceFile(id: number) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}/source-file`,
    method: 'get',
    responseType: 'blob',
    timeout: 120000
  });
}

// 导出交易明细 CSV
export function exportTransactions(id: number) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}/transactions/export`,
    method: 'get',
    responseType: 'blob',
    timeout: 120000
  });
}

/**
 * 交易对手风险警示：批量比对资方库与黑名单。
 * scope=PAGE 只比对 items 当前页行；STATEMENT/HOLDER/ALL 额外返回整段汇总。
 */
export function getRiskAlerts(data: {
  scope?: 'PAGE' | 'STATEMENT' | 'HOLDER' | 'ALL';
  statementId?: number;
  accountHolder?: string;
  items?: Array<{
    key: string;
    name?: string;
    account?: string;
    date?: string;
    direction?: string;
    amount?: number;
  }>;
}) {
  return request({
    url: `${BASE_URI}/bank-statements/risk-alerts`,
    method: 'post',
    data,
    timeout: 60000
  });
}

export interface RiskAlertMark {
  matchType: 'NAME_ACCOUNT' | 'NAME';
  library: 'FUNDER' | 'BLACKLIST_CP' | 'BLACKLIST_CUSTOMER';
  libraryLabel: string;
  targetName: string;
  nameMatched: boolean;
  accountMatched: boolean;
  hint: string;
}

export interface RiskAlertRow {
  transactionId: number | null;
  statementId: number | null;
  transDate: string | null;
  direction: string | null;
  amount: number | null;
  counterpartyName: string | null;
  counterpartyAccount: string | null;
  matchType: 'NAME_ACCOUNT' | 'NAME';
  library: RiskAlertMark['library'];
  libraryLabel: string;
  targetName: string;
  hint: string;
}

export interface RiskAlertGroup {
  targetName: string;
  library: RiskAlertMark['library'];
  libraryLabel: string;
  matchType: 'NAME_ACCOUNT' | 'NAME';
  count: number;
  totalAmount: number;
  rowsTruncated: boolean;
  rows: RiskAlertRow[];
}

export interface RiskAlertSummary {
  totalCount: number;
  targetCount: number;
  totalAmount: number;
  groups: RiskAlertGroup[];
}

export interface RiskAlertResult {
  marks: Record<string, RiskAlertMark>;
  summary: RiskAlertSummary | null;
}

/**
 * 把 blob 响应保存为本地文件
 */
export function saveBlob(response: any, defaultName: string) {
  const blob = response.data instanceof Blob ? response.data : new Blob([response.data]);
  // 优先取响应头里的文件名
  let fileName = defaultName;
  const disposition = response.headers?.['content-disposition'] || '';
  const match = /filename\*?=(?:UTF-8'')?["']?([^"';]+)/i.exec(disposition);
  if (match) {
    fileName = decodeURIComponent(match[1]);
  }
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}

// ==================== 数据共享 ====================

// 可选的分享目标用户列表
export function listShareUsers() {
  return request({
    url: `${BASE_URI}/bank-statements/share-users`,
    method: 'get'
  });
}

// 某流水单当前已分享给哪些用户
export function listShares(id: number) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}/shares`,
    method: 'get'
  });
}

// 把流水单分享给指定用户
export function shareStatement(id: number, userId: number) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}/share`,
    method: 'post',
    data: {userId}
  });
}

// 取消分享
export function revokeShare(id: number, granteeUserId: number) {
  return request({
    url: `${BASE_URI}/bank-statements/${id}/share/${granteeUserId}`,
    method: 'delete'
  });
}
