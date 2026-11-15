<template>
  <div class="page">
    <div class="toolbar">
      <el-select
        v-model="holder"
        filterable
        clearable
        placeholder="选择户名"
        class="holder-select"
        @change="loadAnalysis"
      >
        <el-option
          v-for="item in holders"
          :key="item.accountHolder"
          :label="item.accountHolder"
          :value="item.accountHolder"
        >
          <span>{{ item.accountHolder }}</span>
          <span class="option-extra">{{ item.accountCount }} 个账户</span>
        </el-option>
      </el-select>
      <el-button type="primary" :icon="Search" @click="loadAnalysis">查询</el-button>
      <el-button :icon="Refresh" @click="loadHolders">刷新</el-button>
    </div>

    <template v-if="analysis">
      <el-row :gutter="16" class="stat-row">
        <el-col :xs="12" :sm="12" :md="6">
          <div class="stat-card">
            <div class="stat-label">总入账</div>
            <div class="stat-value income">{{ formatMoney(analysis.totalIncome) }}</div>
            <div class="stat-sub">全部账户收入合计</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="stat-card">
            <div class="stat-label">总出款</div>
            <div class="stat-value expense">{{ formatMoney(analysis.totalExpense) }}</div>
            <div class="stat-sub">全部账户支出合计</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="stat-card">
            <div class="stat-label">净额</div>
            <div class="stat-value" :class="analysis.netAmount >= 0 ? 'income' : 'expense'">
              {{ formatMoney(analysis.netAmount) }}
            </div>
            <div class="stat-sub">入账 - 出款</div>
          </div>
        </el-col>
        <el-col :xs="12" :sm="12" :md="6">
          <div class="stat-card">
            <div class="stat-label">交易笔数</div>
            <div class="stat-value">{{ analysis.transactionCount }}</div>
            <div class="stat-sub">
              {{ analysis.accountCount }} 个账户 · {{ analysis.statementCount }} 份流水单
            </div>
          </div>
        </el-col>
      </el-row>

      <div class="card">
        <div class="card-head">
          <span class="card-title">账户汇总</span>
          <span class="card-sub">
            账期 {{ analysis.startDate || '--' }} ~ {{ analysis.endDate || '--' }}
          </span>
        </div>
        <el-table v-if="!isMobile" :data="analysis.accounts" border size="small" class="table">
          <el-table-column prop="bankName" label="银行" min-width="120"></el-table-column>
          <el-table-column prop="accountNo" label="账号" min-width="150"></el-table-column>
          <el-table-column label="账期" min-width="190">
            <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
          </el-table-column>
          <el-table-column prop="transactionCount" label="笔数" width="80" align="right"></el-table-column>
          <el-table-column label="入账" width="130" align="right">
            <template #default="{ row }"><span class="income">{{ formatMoney(row.totalIncome) }}</span></template>
          </el-table-column>
          <el-table-column label="出款" width="130" align="right">
            <template #default="{ row }"><span class="expense">{{ formatMoney(row.totalExpense) }}</span></template>
          </el-table-column>
        </el-table>
        <div v-else class="card-list">
          <div v-for="row in analysis.accounts" :key="row.id" class="info-card">
            <div class="ic-head">
              <span class="ic-title">{{ row.bankName || '未知银行' }}</span>
              <span class="ic-sub">{{ row.transactionCount }} 笔</span>
            </div>
            <div class="ic-row"><span class="k">账号</span><span class="v">{{ row.accountNo }}</span></div>
            <div class="ic-row"><span class="k">账期</span><span class="v">{{ row.startDate }} ~ {{ row.endDate }}</span></div>
            <div class="ic-row"><span class="k">入账</span><span class="v income">{{ formatMoney(row.totalIncome) }}</span></div>
            <div class="ic-row"><span class="k">出款</span><span class="v expense">{{ formatMoney(row.totalExpense) }}</span></div>
          </div>
          <el-empty v-if="analysis.accounts.length === 0" description="暂无账户数据"></el-empty>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <span class="card-title">月度收支</span>
          <span class="card-sub">共 {{ analysis.monthly.length }} 个月</span>
        </div>
        <el-table v-if="!isMobile" :data="analysis.monthly" border size="small" class="table">
          <el-table-column prop="month" label="月份" width="110"></el-table-column>
          <el-table-column label="入账" align="right">
            <template #default="{ row }"><span class="income">{{ formatMoney(row.income) }}</span></template>
          </el-table-column>
          <el-table-column label="出款" align="right">
            <template #default="{ row }"><span class="expense">{{ formatMoney(row.expense) }}</span></template>
          </el-table-column>
          <el-table-column label="净额" align="right">
            <template #default="{ row }">
              <span :class="row.net >= 0 ? 'income' : 'expense'">{{ formatMoney(row.net) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="count" label="笔数" width="90" align="right"></el-table-column>
        </el-table>
        <div v-else class="card-list">
          <div v-for="row in analysis.monthly" :key="row.month" class="info-card">
            <div class="ic-head">
              <span class="ic-title">{{ row.month }}</span>
              <span class="ic-sub">{{ row.count }} 笔</span>
            </div>
            <div class="ic-row"><span class="k">入账</span><span class="v income">{{ formatMoney(row.income) }}</span></div>
            <div class="ic-row"><span class="k">出款</span><span class="v expense">{{ formatMoney(row.expense) }}</span></div>
            <div class="ic-row">
              <span class="k">净额</span>
              <span class="v" :class="row.net >= 0 ? 'income' : 'expense'">{{ formatMoney(row.net) }}</span>
            </div>
          </div>
          <el-empty v-if="analysis.monthly.length === 0" description="暂无月度数据"></el-empty>
        </div>
      </div>
    </template>

    <el-empty v-else-if="!loading" description="请选择户名查看流水分析"></el-empty>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue';
import {Refresh, Search} from '@element-plus/icons-vue';
import {getHolderAnalysis, listAccountHolders} from '../api/bankStatement';
import {useIsMobile} from '../composables/useIsMobile';

interface AccountHolderOption {
  accountHolder: string;
  statementCount: number;
  accountCount: number;
}

interface AccountSummary {
  id: number;
  bankName: string;
  bankCode: string;
  accountNo: string;
  startDate: string;
  endDate: string;
  transactionCount: number;
  totalIncome: number;
  totalExpense: number;
}

interface MonthlyFlow {
  month: string;
  income: number;
  expense: number;
  net: number;
  count: number;
}

interface HolderAnalysis {
  accountHolder: string;
  statementCount: number;
  accountCount: number;
  transactionCount: number;
  totalIncome: number;
  totalExpense: number;
  netAmount: number;
  startDate: string;
  endDate: string;
  accounts: AccountSummary[];
  monthly: MonthlyFlow[];
}

const holders = ref<AccountHolderOption[]>([]);
const holder = ref('');
const analysis = ref<HolderAnalysis | null>(null);
const loading = ref(false);
const isMobile = useIsMobile();

const formatMoney = (value: any) =>
  Number(value || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});

const loadHolders = () => {
  listAccountHolders().then((res) => {
    holders.value = res.data || [];
    if (!holder.value && holders.value.length > 0) {
      holder.value = holders.value[0].accountHolder;
      loadAnalysis();
    } else if (holders.value.length === 0) {
      analysis.value = null;
    }
  });
};

const loadAnalysis = () => {
  if (!holder.value) {
    analysis.value = null;
    return;
  }
  loading.value = true;
  getHolderAnalysis(holder.value)
    .then((res) => {
      analysis.value = res.data;
    })
    .finally(() => {
      loading.value = false;
    });
};

onMounted(loadHolders);
</script>

<style scoped>
.page {
  min-height: 100%;
  background: #fff;
  padding: 20px;
  box-sizing: border-box;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 20px;
}

.holder-select {
  width: 280px;
  max-width: 100%;
}

.option-extra {
  float: right;
  color: #909399;
  font-size: 12px;
  margin-left: 12px;
}

.stat-row {
  margin-bottom: 16px;
}

.stat-card {
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 12px;
}

.stat-label {
  font-size: 13px;
  color: #606266;
}

.stat-value {
  margin-top: 8px;
  font-size: 22px;
  font-weight: 600;
  word-break: break-all;
}

.stat-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
}

.card-head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 12px;
}

.card-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}

.card-sub {
  font-size: 12px;
  color: #909399;
}

.table {
  width: 100%;
}

.card-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
  background: #fafbfc;
  color: #303133;
}

.ic-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.ic-title {
  font-size: 14px;
  font-weight: 600;
}

.ic-sub {
  font-size: 12px;
  color: #909399;
}

.ic-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 4px 0;
  font-size: 13px;
}

.ic-row .k {
  flex: 0 0 48px;
  color: #909399;
}

.ic-row .v {
  text-align: right;
  word-break: break-all;
}

.income {
  color: #00b42a;
}

.expense {
  color: #f53f3f;
}

@media (max-width: 768px) {
  .page {
    padding: 12px;
  }

  .holder-select {
    width: 100%;
  }

  .stat-value {
    font-size: 18px;
  }
}
</style>
