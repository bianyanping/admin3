import request from '../utils/request';
import {BASE_URI} from './base';

/**
 * 网贷（网络小贷/消费金融）识别与统计接口
 */

// 关键词库分页列表
export function listPlatforms(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/lending/platforms`,
    method: 'get',
    params
  });
}

// 平台下拉选项（含停用平台）
export function listPlatformOptions() {
  return request({
    url: `${BASE_URI}/lending/platforms/options`,
    method: 'get'
  });
}

// 一键初始化预置平台（可重复执行）
export function initPlatforms() {
  return request({
    url: `${BASE_URI}/lending/platforms/init`,
    method: 'post'
  });
}

// 新增平台
export function createPlatform(data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/lending/platforms`,
    method: 'post',
    data
  });
}

// 修改平台
export function updatePlatform(id: number, data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/lending/platforms/${id}`,
    method: 'put',
    data
  });
}

// 删除平台
export function deletePlatform(id: number) {
  return request({
    url: `${BASE_URI}/lending/platforms/${id}`,
    method: 'delete'
  });
}

// 启用/停用平台
export function setPlatformEnabled(id: number, enabled: boolean) {
  return request({
    url: `${BASE_URI}/lending/platforms/${id}/enabled`,
    method: 'patch',
    data: {enabled}
  });
}

// 按当前关键词库重建命中记录
export function refreshMatches() {
  return request({
    url: `${BASE_URI}/lending/matches/refresh`,
    method: 'post',
    timeout: 120000
  });
}

// 客户（账户户名）选项
export function listCustomers() {
  return request({
    url: `${BASE_URI}/lending/customers`,
    method: 'get'
  });
}

// 按月统计
export function getMonthlyStats(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/lending/monthly-stats`,
    method: 'get',
    params,
    timeout: 60000
  });
}

// 网贷命中明细分页
export function listLendingTransactions(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/lending/transactions`,
    method: 'get',
    params
  });
}
