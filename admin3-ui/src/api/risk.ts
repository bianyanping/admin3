import request from '../utils/request';
import {BASE_URI} from './base';

/**
 * 风险数据库：资方、黑名单客户、交易对手主数据与标签
 */

// 汇总：沉淀对手数、消费型剔除数、客户数、黑名单/资方数量、剔除规则
export function getRiskSummary() {
  return request({
    url: `${BASE_URI}/risk/summary`,
    method: 'get',
    timeout: 180000
  });
}

// 强制重建对手主数据
export function syncRiskCounterparties() {
  return request({
    url: `${BASE_URI}/risk/sync`,
    method: 'post',
    timeout: 180000
  });
}

// 交易对手分页：consumer=EXCLUDE/INCLUDE/ONLY，tagCode 过滤标签
export function listRiskCounterparties(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/risk/counterparties`,
    method: 'get',
    params,
    timeout: 180000
  });
}

// 客户/户主列表（账户数、流水单数、征信报告数）
export function listRiskCustomers(params: Record<string, any>) {
  return request({
    url: `${BASE_URI}/risk/customers`,
    method: 'get',
    params,
    timeout: 120000
  });
}

// 标签定义
export function listRiskTags(subjectType?: string) {
  return request({
    url: `${BASE_URI}/risk/tags`,
    method: 'get',
    params: {subjectType}
  });
}

// 批量取标签（页面内出现户名的地方一次性取回）
export function batchRiskTags(subjectType: string, names: string[]) {
  return request({
    url: `${BASE_URI}/risk/tags/batch`,
    method: 'post',
    data: {subjectType, names}
  });
}

// 手工打标
export function linkRiskTag(subjectType: string, subjectKey: string, tagCode: string) {
  return request({
    url: `${BASE_URI}/risk/tags/link`,
    method: 'post',
    data: {subjectType, subjectKey, tagCode}
  });
}

// 取消标记
export function unlinkRiskTag(subjectType: string, subjectKey: string, tagCode: string) {
  return request({
    url: `${BASE_URI}/risk/tags/link`,
    method: 'delete',
    params: {subjectType, subjectKey, tagCode}
  });
}

// 消费型剔除规则
export function listExclusionRules() {
  return request({
    url: `${BASE_URI}/risk/exclusions`,
    method: 'get'
  });
}

export function createExclusionRule(data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/risk/exclusions`,
    method: 'post',
    data
  });
}

export function updateExclusionRule(id: number, data: Record<string, any>) {
  return request({
    url: `${BASE_URI}/risk/exclusions/${id}`,
    method: 'put',
    data
  });
}

export function setExclusionRuleEnabled(id: number, enabled: boolean) {
  return request({
    url: `${BASE_URI}/risk/exclusions/${id}/enabled`,
    method: 'put',
    params: {enabled}
  });
}

export function deleteExclusionRule(id: number) {
  return request({
    url: `${BASE_URI}/risk/exclusions/${id}`,
    method: 'delete'
  });
}
