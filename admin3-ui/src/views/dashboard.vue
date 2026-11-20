<template>
  <div class="dashboard">
    <!-- 筛选栏 -->
    <el-card shadow="never" class="filter-card">
      <div class="filter-bar">
        <span class="filter-label">客户</span>
        <el-select v-model="accountHolder" clearable placeholder="全部客户" class="filter-select" @change="load">
          <el-option v-for="h in holders" :key="h" :label="h" :value="h"></el-option>
        </el-select>
        <span class="filter-label">时间范围</span>
        <el-select v-model="days" class="filter-select" @change="load">
          <el-option label="近7天" :value="7"></el-option>
          <el-option label="近30天" :value="30"></el-option>
          <el-option label="近90天" :value="90"></el-option>
          <el-option label="近一年" :value="365"></el-option>
        </el-select>
        <el-tag type="success" effect="plain" size="small">数据实时更新</el-tag>
        <el-button text :icon="Refresh" class="refresh-btn" @click="load">刷新</el-button>
      </div>
    </el-card>

    <!-- 第一行：核心指标 + 每日收支趋势 -->
    <el-row :gutter="16">
      <el-col :xs="24" :sm="24" :md="8">
        <el-card shadow="hover" class="panel">
          <template #header><span class="panel-title">核心业务指标</span></template>
          <div class="kpi-grid">
            <div class="kpi">
              <div class="kpi-num">{{ stats.totalCount }}</div>
              <div class="kpi-label">总笔数</div>
            </div>
            <div class="kpi">
              <div class="kpi-num">{{ shortMoney(totalAmount) }}</div>
              <div class="kpi-label">总金额</div>
            </div>
            <div class="kpi">
              <div class="kpi-num">{{ stats.todayCount }}</div>
              <div class="kpi-label">今日新增</div>
            </div>
            <div class="kpi">
              <div class="kpi-num warn">{{ stats.anomalyCount }}</div>
              <div class="kpi-label">异常笔数（≥{{ shortMoney(stats.anomalyThreshold) }}）</div>
            </div>
          </div>
          <div class="kpi-extra">
            <span class="income">收入 {{ money(stats.totalIncome) }}</span>
            <span class="expense">支出 {{ money(stats.totalExpense) }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="16">
        <el-card shadow="hover" class="panel">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">资金收支趋势分析</span>
              <span class="panel-sub">近 {{ days }} 天 · 单位：元</span>
            </div>
          </template>
          <div v-if="daily.length === 0" class="empty-tip">当前范围内暂无流水数据</div>
          <schart v-else :key="'trend-' + trendKey" canvasId="trendChart" :options="trendOptions" class="chart"></schart>
        </el-card>
      </el-col>
    </el-row>

    <!-- 第二行：构成占比 + 大额流水动态 -->
    <el-row :gutter="16">
      <el-col :xs="24" :sm="24" :md="8">
        <el-card shadow="hover" class="panel">
          <template #header><span class="panel-title">流水构成占比</span></template>
          <div v-if="categories.length === 0" class="empty-tip">暂无数据</div>
          <div v-for="c in categories" :key="c.name" class="cat-item">
            <div class="cat-line">
              <span class="cat-name">{{ c.name }}</span>
              <span class="cat-pct">{{ c.percent.toFixed(1) }}%</span>
            </div>
            <el-progress
              :percentage="Math.min(c.percent, 100)"
              :show-text="false"
              :stroke-width="8"
              :color="catColor(c.name)"
            ></el-progress>
          </div>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="16">
        <el-card shadow="hover" class="panel">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">最近大额流水动态</span>
              <span class="panel-sub">金额 ≥ {{ shortMoney(stats.anomalyThreshold) }}，点击可查看明细</span>
            </div>
          </template>
          <el-table :data="stats.recentLarge" size="small" height="248" @row-click="goDetail">
            <el-table-column prop="transDate" label="日期" width="110"></el-table-column>
            <el-table-column label="金额" width="140" align="right">
              <template #default="{ row }">
                <span :class="row.amount < 0 ? 'expense' : 'income'">{{ money(row.amount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="对方户名" min-width="170">
              <template #default="{ row }">
                <span>{{ row.counterpartyName }}</span>
                <RiskTags :tags="counterpartyTags.tagsFor(row.counterpartyName)" />
              </template>
            </el-table-column>
            <el-table-column prop="summary" label="摘要" min-width="120" show-overflow-tooltip></el-table-column>
            <template #empty>当前范围内没有大额流水</template>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 第三行：近7天收支对比 + 对手方 TOP10 -->
    <el-row :gutter="16">
      <el-col :xs="24" :sm="24" :md="12">
        <el-card shadow="hover" class="panel">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">最近 7 天收支对比</span>
              <span class="panel-sub">单位：元</span>
            </div>
          </template>
          <schart :key="'bar-' + barKey" canvasId="barChart" :options="barOptions" class="chart"></schart>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="24" :md="12">
        <el-card shadow="hover" class="panel">
          <template #header>
            <div class="panel-head">
              <span class="panel-title">交易对手金额 TOP 10</span>
              <span class="panel-sub">按累计交易金额排序</span>
            </div>
          </template>
          <div v-if="topCounterparties.length === 0" class="empty-tip">暂无数据</div>
          <div v-for="(c, i) in topCounterparties" :key="c.name" class="rank-item">
            <span class="rank-no" :class="{ top3: i < 3 }">{{ i + 1 }}</span>
            <div class="rank-body">
              <div class="rank-line">
                <span class="rank-name">{{ c.name }}</span>
                <RiskTags :tags="counterpartyTags.tagsFor(c.name)" />
                <span class="rank-amount">{{ money(c.amount) }}</span>
              </div>
              <div class="rank-bar">
                <div class="rank-bar-inner" :style="{ width: rankWidth(c.amount) + '%' }"></div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts" name="dashboard">
import {computed, onMounted, reactive, ref} from 'vue';
import {useRouter} from 'vue-router';
import {Refresh} from '@element-plus/icons-vue';
import Schart from 'vue-schart';
import {getDashboard} from '../api/bankStatement';
import {useRiskTags} from '../composables/useRiskTags';
import RiskTags from '../components/RiskTags.vue';

const router = useRouter();

const days = ref(30);
const accountHolder = ref('');
const holders = ref<string[]>([]);
const trendKey = ref(0);
const barKey = ref(0);

const stats = reactive<any>({
  totalCount: 0,
  totalIncome: 0,
  totalExpense: 0,
  todayCount: 0,
  anomalyCount: 0,
  anomalyThreshold: 50000,
  daily: [],
  categories: [],
  topCounterparties: [],
  recentLarge: [],
});

const daily = computed<any[]>(() => stats.daily || []);
const categories = computed<any[]>(() => stats.categories || []);
const topCounterparties = computed<any[]>(() => stats.topCounterparties || []);
const counterpartyTags = useRiskTags('COUNTERPARTY');
const totalAmount = computed(() => Number(stats.totalIncome || 0) + Number(stats.totalExpense || 0));

const money = (v: any) => Number(v || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});

/** 大额数字压缩为「万 / 亿」，用于指标卡 */
const shortMoney = (v: any) => {
  const n = Math.abs(Number(v || 0));
  if (n >= 100000000) return (n / 100000000).toFixed(2) + ' 亿';
  if (n >= 10000) return (n / 10000).toFixed(1) + ' 万';
  return n.toLocaleString('zh-CN', {maximumFractionDigits: 0});
};

const catColor = (name: string) => {
  const map: Record<string, string> = {
    转账: '#409eff', 消费: '#67c23a', 工资: '#e6a23c',
    手续费: '#909399', 利息: '#f56c6c', 其他: '#b1b3b8',
  };
  return map[name] || '#b1b3b8';
};

const trendOptions = ref<any>({});
const barOptions = ref<any>({});

const maxTop = computed(() => Math.max(...topCounterparties.value.map((c: any) => Number(c.amount || 0)), 1));
const rankWidth = (amount: any) => Math.max((Number(amount || 0) / maxTop.value) * 100, 2);

const buildCharts = () => {
  const md = (d: string) => d.slice(5);
  trendOptions.value = {
    type: 'line',
    title: {text: ''},
    xRorate: 30,
    labels: daily.value.map((d: any) => md(d.date)),
    datasets: [
      {label: '收入', data: daily.value.map((d: any) => Number(d.income || 0)), fillColor: 'rgba(103,194,58,0.12)', strokeColor: '#67c23a', pointColor: '#67c23a'},
      {label: '支出', data: daily.value.map((d: any) => Number(d.expense || 0)), fillColor: 'rgba(245,108,108,0.12)', strokeColor: '#f56c6c', pointColor: '#f56c6c'},
    ],
  };

  const last7 = daily.value.slice(-7);
  barOptions.value = {
    type: 'bar',
    title: {text: ''},
    labels: last7.map((d: any) => md(d.date)),
    datasets: [
      {label: '收入', data: last7.map((d: any) => Number(d.income || 0)), fillColor: '#67c23a'},
      {label: '支出', data: last7.map((d: any) => Number(d.expense || 0)), fillColor: '#f56c6c'},
    ],
  };
  trendKey.value++;
  barKey.value++;
};

const load = () => {
  getDashboard({
    days: days.value,
    accountHolder: accountHolder.value || undefined,
  }).then((res: any) => {
    Object.assign(stats, res.data || {});
    // 户名下拉直接使用首页接口返回的当前组织范围数据，避免额外拉取流水列表
    holders.value = res.data?.holders || [];
    // TOP 对手与大额流水中的对手名称批量取标签
    counterpartyTags.load([
      ...(stats.topCounterparties || []).map((item: any) => item.name),
      ...(stats.recentLarge || []).map((item: any) => item.counterpartyName),
    ]);
    buildCharts();
  });
};

const goDetail = (row: any) => {
  if (row?.statementId) {
    router.push(`/bank-statements/${row.statementId}/transactions`);
  }
};

onMounted(() => {
  load();
});
</script>

<style scoped>
.dashboard {
  padding-bottom: 8px;
}

.filter-card {
  margin-bottom: 16px;
  border-radius: 6px;
}

.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.filter-label {
  font-size: 13px;
  color: #606266;
}

.filter-select {
  width: 150px;
}

.refresh-btn {
  margin-left: auto;
}

.panel {
  border-radius: 6px;
  margin-bottom: 16px;
}

.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.panel-title {
  font-weight: 600;
  font-size: 15px;
  color: #303133;
}

.panel-sub {
  font-size: 12px;
  color: #909399;
}

.chart {
  width: 100%;
  height: 268px;
}

/* ===== 核心指标 ===== */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.kpi {
  background: #f7f9fc;
  border-radius: 6px;
  padding: 14px 12px;
  text-align: center;
}

.kpi-num {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  line-height: 1.2;
}

.kpi-num.warn {
  color: #e6a23c;
}

.kpi-label {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}

.kpi-extra {
  display: flex;
  justify-content: space-between;
  margin-top: 12px;
  font-size: 13px;
}

.income {
  color: #67c23a;
}

.expense {
  color: #f56c6c;
}

/* ===== 构成占比 ===== */
.cat-item {
  margin-bottom: 14px;
}

.cat-line {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: #606266;
  margin-bottom: 6px;
}

.cat-pct {
  color: #909399;
}

/* ===== 对手方排行 ===== */
.rank-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
}

.rank-no {
  flex: none;
  width: 20px;
  height: 20px;
  line-height: 20px;
  text-align: center;
  border-radius: 4px;
  font-size: 12px;
  color: #909399;
  background: #f2f3f5;
}

.rank-no.top3 {
  color: #fff;
  background: #409eff;
}

.rank-body {
  flex: 1;
  min-width: 0;
}

.rank-line {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 13px;
  margin-bottom: 4px;
}

.rank-name {
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-amount {
  flex: none;
  color: #606266;
}

.rank-bar {
  height: 6px;
  background: #f2f3f5;
  border-radius: 3px;
  overflow: hidden;
}

.rank-bar-inner {
  height: 100%;
  background: linear-gradient(90deg, #79bbff, #409eff);
  border-radius: 3px;
}

.empty-tip {
  padding: 30px 0;
  text-align: center;
  color: #909399;
  font-size: 13px;
}

/* 窄屏：面板单列堆叠（列宽由 el-col 的 :xs/:md 响应式属性控制） */
@media (max-width: 768px) {
  .filter-bar {
    gap: 8px;
  }

  .filter-select {
    flex: 1 1 120px;
    width: auto;
  }

  .refresh-btn {
    margin-left: 0;
  }

  .panel :deep(.el-card__body) {
    padding: 12px;
  }

  .panel-sub {
    display: none;
  }

  .kpi-grid {
    gap: 8px;
  }

  .kpi {
    padding: 10px 8px;
  }

  .kpi-num {
    font-size: 18px;
  }

  .kpi-extra {
    flex-direction: column;
    gap: 4px;
    font-size: 12px;
  }

  .chart {
    height: 200px;
  }

  .rank-line {
    font-size: 12px;
  }
}
</style>
