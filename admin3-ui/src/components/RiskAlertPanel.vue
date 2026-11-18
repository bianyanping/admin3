<template>
  <div v-if="summary && summary.totalCount > 0" class="risk-alert">
    <div class="risk-alert-bar">
      <el-icon class="risk-alert-icon"><WarningFilled /></el-icon>
      <span class="risk-alert-text">
        风险往来 <b>{{ summary.totalCount }}</b> 笔，涉及 <b>{{ summary.targetCount }}</b> 个对手，
        合计 <b>{{ formatMoney(summary.totalAmount) }}</b> 元
      </span>
      <el-button text type="primary" size="small" class="risk-alert-btn" @click="visible = true">
        展开逐笔
      </el-button>
    </div>

    <el-drawer v-model="visible" title="风险往来逐笔" size="760px" append-to-body>
      <div v-for="group in summary.groups" :key="group.library + '|' + group.targetName" class="risk-group">
        <div class="risk-group-head">
          <span class="risk-group-name">{{ group.targetName }}</span>
          <el-tag size="small" :type="tagType(group.library)" disable-transitions>{{ group.libraryLabel }}</el-tag>
          <el-tag v-if="group.matchType === 'NAME_ACCOUNT'" size="small" type="danger" effect="dark"
                  disable-transitions>名称+卡号</el-tag>
          <span class="risk-group-sub">
            {{ group.count }} 笔 · 合计 {{ formatMoney(group.totalAmount) }} 元
          </span>
        </div>
        <table class="risk-table">
          <colgroup>
            <col style="width: 92px" />
            <col style="width: 46px" />
            <col style="width: 112px" />
            <col />
            <col style="width: 62px" />
          </colgroup>
          <thead>
            <tr>
              <th>日期</th>
              <th>方向</th>
              <th class="ta-r">金额</th>
              <th>命中原因</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in group.rows" :key="row.transactionId ?? `${row.transDate || ''}-${row.amount ?? ''}`">
              <td>{{ row.transDate || '--' }}</td>
              <td :class="row.direction === '入' ? 'income' : 'expense'">
                {{ row.direction === '入' ? '入账' : row.direction === '出' ? '出账' : (row.direction || '--') }}
              </td>
              <td class="ta-r" :class="row.direction === '入' ? 'income' : 'expense'">
                {{ formatMoney(row.amount) }}
              </td>
              <td>
                <div>{{ row.hint }}</div>
                <div class="risk-row-basis">
                  命中库：{{ row.libraryLabel }} · 名称{{ row.matchType === 'NAME_ACCOUNT' ? '与卡号均一致' : '命中' }}
                </div>
              </td>
              <td>
                <el-button v-if="row.statementId" text type="primary" size="small"
                           @click="goStatement(row)">原流水</el-button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="group.rowsTruncated" class="risk-more">
          仅展示最近 {{ group.rows.length }} 笔，笔数与金额按全量统计
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import {ref} from 'vue';
import {useRouter} from 'vue-router';
import {WarningFilled} from '@element-plus/icons-vue';
import type {RiskAlertGroup, RiskAlertRow, RiskAlertSummary} from '../api/bankStatement';

defineProps<{ summary: RiskAlertSummary | null }>();
const router = useRouter();
const visible = ref(false);

const formatMoney = (value: any) =>
  Number(value || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});

const tagType = (library: RiskAlertGroup['library']) => {
  if (library === 'BLACKLIST_CP' || library === 'BLACKLIST_CUSTOMER') return 'danger';
  return 'warning';
};

const goStatement = (row: RiskAlertRow) => {
  router.push({
    path: `/bank-statements/${row.statementId}/transactions`,
    query: row.counterpartyName ? {keyword: row.counterpartyName} : {}
  });
};
</script>

<style scoped>
.risk-alert {
  margin-bottom: 12px;
}

.risk-alert-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  background: #fef0f0;
  border: 1px solid #fde2e2;
  border-left: 4px solid #f56c6c;
  border-radius: 4px;
}

.risk-alert-icon {
  color: #f56c6c;
  font-size: 16px;
}

.risk-alert-text {
  font-size: 13px;
  color: #606266;
}

.risk-alert-text b {
  color: #f56c6c;
  font-weight: 600;
}

.risk-alert-btn {
  margin-left: auto;
}

.risk-group {
  margin-bottom: 18px;
}

.risk-group-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding-bottom: 6px;
  border-bottom: 1px solid #ebeef5;
}

.risk-group-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.risk-group-sub {
  margin-left: auto;
  font-size: 12px;
  color: #909399;
}

.risk-table {
  width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 12px;
}

.risk-table th,
.risk-table td {
  padding: 5px 6px;
  border-bottom: 1px dashed #ebeef5;
  vertical-align: top;
  word-break: break-all;
  box-sizing: border-box;
}

.risk-table th {
  text-align: left;
  color: #909399;
  font-weight: 600;
}

.risk-table th.ta-r,
.risk-table td.ta-r {
  text-align: right;
  word-break: keep-all;
}

.risk-row-basis {
  color: #909399;
  font-size: 11px;
  margin-top: 2px;
}

.risk-more {
  font-size: 12px;
  color: #909399;
  padding: 6px 2px 0;
}

.income {
  color: #00b42a;
}

.expense {
  color: #f53f3f;
}

@media (max-width: 768px) {
  .risk-alert-btn {
    margin-left: 0;
  }
}
</style>
