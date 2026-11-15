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
