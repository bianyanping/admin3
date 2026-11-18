<template>
  <div class="page">
    <div class="handle-box">
      <el-date-picker
        v-model="range"
        type="monthrange"
        range-separator="至"
        start-placeholder="开始月份"
        end-placeholder="结束月份"
        value-format="YYYY-MM"
        class="range-picker"
        clearable
      ></el-date-picker>
      <el-select v-model="customer" placeholder="客户（户名）" class="customer-select" clearable filterable
                 @change="handleCustomerChange">
        <el-option v-for="c in customerOptions" :key="c.value" :label="c.label" :value="c.value">
          <span>{{ c.label }}</span>
          <span class="option-extra">{{ c.accountCount }} 个账户</span>
        </el-option>
      </el-select>
      <el-button type="primary" :icon="Search" @click="loadStats">查询</el-button>
      <el-button
        type="warning"
        :icon="RefreshRight"
        :loading="refreshing"
        v-action:lending:manage
        @click="handleRefresh"
      >重新匹配流水</el-button>
      <span class="match-tip" v-if="stats">
        已扫描 {{ stats.totalTransactionCount }} 笔流水，命中 {{ stats.matchedCount }} 笔，命中率 {{ stats.hitRate }}%
      </span>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card">
          <div class="stat-label">收到网贷合计</div>
          <div class="stat-value income">{{ formatMoney(stats?.totalReceived?.amount) }}</div>
          <div class="stat-sub">{{ stats?.totalReceived?.count || 0 }} 笔 · 涉及 {{ stats?.totalReceived?.platformCount || 0 }} 家</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card">
          <div class="stat-label">扣款网贷合计</div>
          <div class="stat-value expense">{{ formatMoney(stats?.totalDeducted?.amount) }}</div>
          <div class="stat-sub">{{ stats?.totalDeducted?.count || 0 }} 笔 · 涉及 {{ stats?.totalDeducted?.platformCount || 0 }} 家</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card">
          <div class="stat-label">命中笔数</div>
          <div class="stat-value">{{ stats?.matchedCount || 0 }}</div>
          <div class="stat-sub">不含不计收支的其他流水 {{ stats?.otherCount || 0 }} 笔</div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <div class="stat-card">
          <div class="stat-label">命中率</div>
          <div class="stat-value">{{ stats?.hitRate || 0 }}%</div>
          <div class="stat-sub">命中笔数 / 全部入库流水</div>
        </div>
      </el-col>
    </el-row>

    <div class="card">
      <div class="card-head">
        <span class="card-title">按客户汇总</span>
        <span class="card-sub">点击行可按该客户筛选；未命名户主表示流水单本身没有户名</span>
      </div>
      <el-table :data="stats?.customers || []" border size="small" class="table"
                row-class-name="clickable-row" @row-click="handleCustomerRowClick">
        <el-table-column label="客户" min-width="180">
          <template #default="{ row }">
            <div>
              {{ row.accountHolder }}
              <RiskTags :tags="customerTags.tagsFor(row.accountHolder)" />
            </div>
            <div class="sub">{{ row.accountCount }} 个账户</div>
          </template>
        </el-table-column>
        <el-table-column prop="matchCount" label="命中笔数" width="100" align="center"></el-table-column>
        <el-table-column label="收到网贷" align="right" min-width="160">
          <template #default="{ row }">
            <span class="income">{{ formatMoney(row.received.amount) }}</span>
            <span class="sub">（{{ row.received.count }} 笔 / {{ row.received.platformCount }} 家）</span>
          </template>
        </el-table-column>
        <el-table-column label="扣款网贷" align="right" min-width="160">
          <template #default="{ row }">
            <span class="expense">{{ formatMoney(row.deducted.amount) }}</span>
            <span class="sub">（{{ row.deducted.count }} 笔 / {{ row.deducted.platformCount }} 家）</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="stats && stats.customers.length === 0" description="暂无命中客户"></el-empty>
    </div>

    <div class="card">
      <div class="card-head">
        <span class="card-title">月度网贷统计</span>
        <span class="card-sub">收到 = 入账方向，扣款 = 出账方向；同一笔命中多个关键词时只计一次</span>
      </div>
      <el-table :data="stats?.months || []" border size="small" class="table">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand-wrap">
              <div class="expand-col">
                <div class="expand-title income">收到网贷 · {{ row.received.platformCount }} 家</div>
                <div v-for="p in row.received.platforms" :key="'r' + p.platformId" class="expand-row">
                  <span>{{ p.platformName }}</span>
                  <span>{{ formatMoney(p.amount) }}（{{ p.count }} 笔）</span>
                </div>
                <div v-if="!row.received.platforms.length" class="muted">无</div>
              </div>
              <div class="expand-col">
                <div class="expand-title expense">扣款网贷 · {{ row.deducted.platformCount }} 家</div>
                <div v-for="p in row.deducted.platforms" :key="'d' + p.platformId" class="expand-row">
                  <span>{{ p.platformName }}</span>
                  <span>{{ formatMoney(p.amount) }}（{{ p.count }} 笔）</span>
                </div>
                <div v-if="!row.deducted.platforms.length" class="muted">无</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="month" label="月份" width="110"></el-table-column>
        <el-table-column label="收到网贷" align="right" min-width="150">
          <template #default="{ row }">
            <span class="income">{{ formatMoney(row.received.amount) }}</span>
            <span class="sub">（{{ row.received.count }} 笔）</span>
          </template>
        </el-table-column>
        <el-table-column label="收到涉及平台" width="120" align="center">
          <template #default="{ row }">{{ row.received.platformCount }} 家</template>
        </el-table-column>
        <el-table-column label="扣款网贷" align="right" min-width="150">
          <template #default="{ row }">
            <span class="expense">{{ formatMoney(row.deducted.amount) }}</span>
            <span class="sub">（{{ row.deducted.count }} 笔）</span>
          </template>
        </el-table-column>
        <el-table-column label="扣款涉及平台" width="120" align="center">
          <template #default="{ row }">{{ row.deducted.platformCount }} 家</template>
        </el-table-column>
        <el-table-column label="其他" width="80" align="center">
          <template #default="{ row }">{{ row.otherCount }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="stats && stats.months.length === 0" description="暂无命中数据，请先初始化关键词库并重新匹配"></el-empty>
    </div>

    <div class="card">
      <div class="card-head">
        <span class="card-title">网贷命中明细</span>
        <span class="card-sub">客户筛选见页面顶部；点击「原流水」可跳转到对应流水单明细</span>
      </div>
      <div class="handle-box detail-filter">
        <el-date-picker
          v-model="detail.month"
          type="month"
          placeholder="月份"
          value-format="YYYY-MM"
          class="month-picker"
          clearable
        ></el-date-picker>
        <el-select v-model="detail.platformId" placeholder="平台" class="platform-select" clearable filterable>
          <el-option v-for="p in platformOptions" :key="p.id" :label="p.name" :value="p.id">
            <span>{{ p.name }}</span>
            <span v-if="!p.enabled" class="disabled-tag">已停用</span>
          </el-option>
        </el-select>
        <el-select v-model="detail.direction" placeholder="方向" class="direction-select" clearable>
          <el-option label="收到（入账）" value="入"></el-option>
          <el-option label="扣款（出账）" value="出"></el-option>
          <el-option label="其他" value="其他"></el-option>
        </el-select>
        <el-button type="primary" :icon="Search" @click="handleSearchDetail">搜索</el-button>
      </div>

      <el-table :data="detailRows" border size="small" class="table">
        <el-table-column prop="transDate" label="交易日期" width="110"></el-table-column>
        <el-table-column label="客户" min-width="170">
          <template #default="{ row }">
            <div>
              {{ row.accountHolder || '未命名户主' }}
              <RiskTags :tags="customerTags.tagsFor(row.accountHolder)" />
            </div>
            <div class="sub">{{ row.accountNo }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="bankName" label="银行" min-width="120"></el-table-column>
        <el-table-column label="对方户名" min-width="160">
          <template #default="{ row }">
            <span>{{ row.counterpartyName }}</span>
            <RiskTags :tags="counterpartyTags.tagsFor(row.counterpartyName)" />
          </template>
        </el-table-column>
        <el-table-column prop="summary" label="摘要" min-width="180" show-overflow-tooltip></el-table-column>
        <el-table-column label="方向" width="70" align="center">
          <template #default="{ row }">
            <el-tag :type="row.direction === '入' ? 'success' : row.direction === '出' ? 'danger' : 'info'"
                    size="small" disable-transitions>{{ row.direction || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">
            <span :class="row.direction === '出' ? 'expense' : 'income'">
              {{ formatMoney(Math.abs(Number(row.amount || 0))) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="platformName" label="命中平台" width="120"></el-table-column>
        <el-table-column prop="keyword" label="命中关键词" width="110"></el-table-column>
        <el-table-column label="命中字段" width="90" align="center">
          <template #default="{ row }">{{ fieldLabel(row.matchedField) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="goStatement(row)">原流水</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="detailRows.length === 0" description="暂无命中明细"></el-empty>

      <div class="pagination">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :current-page="detail.page"
          :page-size="detail.size"
          :total="detailTotal"
          @current-change="handlePageChange"
        ></el-pagination>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue';
import {useRouter} from 'vue-router';
import {ElMessage, ElMessageBox} from 'element-plus';
import {RefreshRight, Search} from '@element-plus/icons-vue';
import {
  getMonthlyStats,
  listCustomers,
  listLendingTransactions,
  listPlatformOptions,
  refreshMatches,
} from '../api/lending';
import {useRiskTags} from '../composables/useRiskTags';
import RiskTags from '../components/RiskTags.vue';

interface PlatformAmount {
  platformId: number;
  platformName: string;
  amount: number;
  count: number;
}

interface DirectionStats {
  amount: number;
  count: number;
  platformCount: number;
  platforms: PlatformAmount[];
}

interface MonthlyRow {
  month: string;
  received: DirectionStats;
  deducted: DirectionStats;
  otherCount: number;
}

interface CustomerSummary {
  value: string;
  accountHolder: string;
  accountCount: number;
  matchCount: number;
  received: DirectionStats;
  deducted: DirectionStats;
}

interface MonthlyStats {
  months: MonthlyRow[];
  totalReceived: DirectionStats;
  totalDeducted: DirectionStats;
  otherCount: number;
  matchedCount: number;
  totalTransactionCount: number;
  hitRate: number;
  customers: CustomerSummary[];
}

interface MatchRow {
  id: number;
  transactionId: number;
  statementId: number;
  accountHolder: string;
  bankName: string;
  accountNo: string;
  transDate: string;
  amount: number;
  direction: string;
  counterpartyName: string;
  summary: string;
  platformName: string;
  keyword: string;
  matchedField: string;
}

interface PlatformOption {
  id: number;
  name: string;
  enabled: boolean;
}

interface CustomerOption {
  value: string;
  label: string;
  accountCount: number;
}

const router = useRouter();
const range = ref<[string, string] | null>(null);
const customer = ref('');
const stats = ref<MonthlyStats | null>(null);
const refreshing = ref(false);
const customerTags = useRiskTags('CUSTOMER');
const counterpartyTags = useRiskTags('COUNTERPARTY');

const platformOptions = ref<PlatformOption[]>([]);
const customerOptions = ref<CustomerOption[]>([]);
const detailRows = ref<MatchRow[]>([]);
const detailTotal = ref(0);
const detail = ref({
  month: '',
  platformId: undefined as number | undefined,
  direction: '',
  page: 1,
  size: 20,
});

const formatMoney = (value: any) =>
  Number(value || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});

const fieldLabel = (field: string) =>
  field === 'COUNTERPARTY_NAME' ? '户名' : field === 'SUMMARY' ? '摘要' : (field || '-');

const loadStats = () => {
  getMonthlyStats({
    startMonth: range.value?.[0] || undefined,
    endMonth: range.value?.[1] || undefined,
    accountHolder: customer.value || undefined,
  }).then((res) => {
    stats.value = res.data;
    customerTags.load((res.data?.customers || []).map((row: CustomerSummary) => row.accountHolder));
  });
};

const loadDetail = () => {
  listLendingTransactions({
    month: detail.value.month || undefined,
    platformId: detail.value.platformId,
    direction: detail.value.direction || undefined,
    accountHolder: customer.value || undefined,
    page: detail.value.page,
    size: detail.value.size,
  }).then((res) => {
    detailRows.value = res.data?.content || [];
    detailTotal.value = res.data?.totalElements || 0;
    customerTags.load(detailRows.value.map((row) => row.accountHolder));
    counterpartyTags.load(detailRows.value.map((row) => row.counterpartyName));
  });
};

const loadOptions = () => {
  listPlatformOptions().then((res) => {
    platformOptions.value = res.data || [];
  });
  listCustomers().then((res) => {
    customerOptions.value = res.data || [];
    customerTags.load(customerOptions.value.map((item) => item.label));
  });
};

const handleCustomerChange = () => {
  detail.value.page = 1;
  loadStats();
  loadDetail();
};

const handleCustomerRowClick = (row: CustomerSummary) => {
  customer.value = customer.value === row.value ? '' : row.value;
  handleCustomerChange();
};

const handleSearchDetail = () => {
  detail.value.page = 1;
  loadDetail();
};

const handlePageChange = (page: number) => {
  detail.value.page = page;
  loadDetail();
};

const handleRefresh = () => {
  ElMessageBox.confirm(
    '将按当前启用的关键词库重新扫描全部流水，只重建命中记录，不会修改流水数据。是否继续？',
    '重新匹配',
    {type: 'warning'}
  ).then(() => {
    refreshing.value = true;
    refreshMatches()
      .then((res) => {
        const r = res.data;
        ElMessage.success(
          `匹配完成：扫描 ${r.scannedCount} 笔，命中 ${r.matchedCount} 笔（收 ${r.incomeCount} / 支 ${r.expenseCount} / 其他 ${r.otherCount}），耗时 ${r.elapsedMs} ms`
        );
        loadStats();
        loadDetail();
      })
      .finally(() => {
        refreshing.value = false;
      });
  }).catch(() => {
  });
};

const goStatement = (row: MatchRow) => {
  router.push(`/bank-statements/${row.statementId}/transactions`);
};

onMounted(() => {
  loadOptions();
  loadStats();
  loadDetail();
});
</script>

<style scoped>
.page {
  min-height: 100%;
  background: #fff;
  padding: 20px;
  box-sizing: border-box;
}

.handle-box {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.range-picker {
  width: 280px;
  max-width: 100%;
}

.customer-select {
  width: 220px;
  max-width: 100%;
}

.option-extra {
  float: right;
  color: #909399;
  font-size: 12px;
  margin-left: 12px;
}

:deep(.clickable-row) {
  cursor: pointer;
}

.match-tip {
  font-size: 12px;
  color: #909399;
}

.detail-filter {
  margin-bottom: 12px;
}

.month-picker {
  width: 150px;
}

.platform-select {
  width: 180px;
}

.direction-select {
  width: 150px;
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

.sub {
  color: #909399;
  font-size: 12px;
  margin-left: 4px;
}

.income {
  color: #00b42a;
}

.expense {
  color: #f53f3f;
}

.muted {
  color: #c0c4cc;
  font-size: 12px;
}

.disabled-tag {
  float: right;
  color: #909399;
  font-size: 12px;
}

.expand-wrap {
  display: flex;
  gap: 32px;
  padding: 8px 16px;
  flex-wrap: wrap;
}

.expand-col {
  flex: 1;
  min-width: 240px;
}

.expand-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.expand-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  font-size: 13px;
  padding: 4px 0;
  border-bottom: 1px dashed #ebeef5;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

@media (max-width: 768px) {
  .page {
    padding: 12px;
  }

  .range-picker,
  .customer-select,
  .month-picker,
  .platform-select,
  .direction-select {
    width: 100%;
  }
}
</style>
