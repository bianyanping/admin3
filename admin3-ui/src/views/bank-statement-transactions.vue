<template>
  <div class="page">
    <div class="summary-card" v-if="statement">
      <el-button :icon="ArrowLeft" @click="$router.back()" style="float: right;">返回</el-button>
      <h3>
        {{ statement.bankName }} · {{ statement.accountHolder }}
        <RiskTags :tags="customerTags.tagsFor(statement.accountHolder)" />
      </h3>
      <div class="summary-grid">
        <div><label>账号</label><span>{{ statement.accountNo }}</span></div>
        <div><label>币种</label><span>{{ statement.currency }}</span></div>
        <div><label>账期</label><span>{{ statement.startDate }} ~ {{ statement.endDate }}</span></div>
        <div><label>总笔数</label><span>{{ statement.transactionCount }}</span></div>
        <div><label class="income">总收入</label><span class="income">{{ statement.totalIncome }} ({{ statement.incomeCount }}笔)</span></div>
        <div><label class="expense">总支出</label><span class="expense">{{ statement.totalExpense }} ({{ statement.expenseCount }}笔)</span></div>
      </div>
    </div>

    <RiskAlertPanel :summary="riskSummary" />

    <div class="handle-box">
      <el-select v-model="query.direction" placeholder="借贷方向" class="handle-select" clearable>
        <el-option label="收入" value="入"></el-option>
        <el-option label="支出" value="出"></el-option>
      </el-select>
      <el-date-picker v-model="query.dateRange" type="daterange" range-separator="至"
        start-placeholder="交易开始" end-placeholder="交易结束" value-format="YYYY-MM-DD"
        :style="isMobile ? { width: '100%', flex: 'none', boxSizing: 'border-box' } : { width: '260px', flex: 'none' }" clearable></el-date-picker>
      <el-input v-model="query.minAmount" placeholder="最小金额" class="handle-input-small" clearable></el-input>
      <el-input v-model="query.maxAmount" placeholder="最大金额" class="handle-input-small" clearable></el-input>
      <el-input v-model="query.keyword" placeholder="对方/摘要/备注关键字" class="handle-input" clearable></el-input>
      <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      <el-button type="success" :icon="Download" @click="handleExport" v-action:bankStatement:transaction:export>导出 CSV</el-button>
    </div>

    <div v-if="!isMobile" ref="tableWrap" class="table-wrap">
      <el-table :data="tableData" :height="tableHeight" border class="table" header-cell-class-name="table-header" :row-class-name="riskRowClass">
      <el-table-column prop="seqNo" label="序号" width="60" align="center"></el-table-column>
      <el-table-column prop="transDate" label="交易日期" width="110"></el-table-column>
      <el-table-column prop="transTime" label="交易时间" width="90"></el-table-column>
      <el-table-column prop="amount" label="金额" width="140" align="right">
        <template #default="{ row }">
          <span :class="row.amount < 0 ? 'expense' : 'income'">{{ row.amount }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="balance" label="余额" width="140" align="right"></el-table-column>
      <el-table-column label="方向" width="70" align="center">
        <template #default="{ row }">
          <span
            :title="directionTitle(row)"
            :class="{ 'direction-mapped': !!directionTitle(row) }"
          >{{ row.direction || (row.amount < 0 ? '出' : '入') }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="counterpartyAccount" label="对方账号" min-width="150"></el-table-column>
      <el-table-column label="对方户名" min-width="160">
        <template #default="{ row }">
          <span>{{ row.counterpartyName }}</span>
          <RiskTags :tags="counterpartyTags.tagsFor(row.counterpartyName)" />
          <el-tooltip v-if="riskMarks[row.id]" placement="top">
            <span class="risk-flag" :class="riskMarks[row.id].matchType === 'NAME_ACCOUNT' ? 'risk-flag--both' : 'risk-flag--name'">
              {{ riskBadge(riskMarks[row.id]) }}
            </span>
            <template #content>
              <div class="risk-tip">
                <div class="risk-tip-title">{{ riskMarks[row.id].hint }}</div>
                <div class="risk-tip-sub">命中库：{{ riskMarks[row.id].libraryLabel }}</div>
                <div class="risk-tip-sub">
                  对方名称：{{ riskMarks[row.id].targetName }}（{{ riskMarks[row.id].nameMatched ? '一致' : '不一致' }}）
                </div>
                <div class="risk-tip-sub">
                  对方卡号：{{ riskMarks[row.id].accountMatched ? '与库中卡号一致' : '未匹配' }}
                </div>
              </div>
            </template>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="channel" label="渠道" width="100"></el-table-column>
      <el-table-column prop="summary" label="摘要" min-width="140"></el-table-column>
      <el-table-column prop="remark" label="备注" min-width="160"></el-table-column>
    </el-table>
    </div>

    <!-- 移动端：卡片式明细 -->
    <div v-else class="card-list">
      <div class="txn-card" v-for="(row, idx) in tableData" :key="row.id ?? idx" :class="riskCardClass(row)">
        <div class="card-head">
          <span class="card-date">{{ row.transDate }} {{ row.transTime }}</span>
          <el-tag :type="row.amount < 0 ? 'danger' : 'success'" size="small" disable-transitions :title="directionTitle(row)">
            {{ row.direction || (row.amount < 0 ? '出' : '入') }}
          </el-tag>
        </div>
        <div class="card-amount" :class="row.amount < 0 ? 'expense' : 'income'">{{ row.amount }}</div>
        <div v-if="riskMarks[row.id]" class="card-risk"
             :class="riskMarks[row.id].matchType === 'NAME_ACCOUNT' ? 'card-risk--both' : 'card-risk--name'">
          <b>{{ riskBadge(riskMarks[row.id]) }}</b> · {{ riskMarks[row.id].hint }}
          <div class="risk-tip-sub">
            命中库：{{ riskMarks[row.id].libraryLabel }} · 名称{{ riskMarks[row.id].nameMatched ? '一致' : '不一致' }} ·
            卡号{{ riskMarks[row.id].accountMatched ? '一致' : '未匹配' }}
          </div>
        </div>
        <div class="card-row"><span class="k">余额</span><span class="v">{{ row.balance }}</span></div>
        <div class="card-row" v-if="row.counterpartyName">
          <span class="k">对方</span>
          <span class="v">
            {{ row.counterpartyName }}
            <RiskTags :tags="counterpartyTags.tagsFor(row.counterpartyName)" />
          </span>
        </div>
        <div class="card-row" v-if="row.counterpartyAccount"><span class="k">账号</span><span class="v">{{ row.counterpartyAccount }}</span></div>
        <div class="card-row" v-if="row.summary"><span class="k">摘要</span><span class="v">{{ row.summary }}</span></div>
        <div class="card-row" v-if="row.remark"><span class="k">备注</span><span class="v">{{ row.remark }}</span></div>
        <div class="card-row" v-if="row.channel"><span class="k">渠道</span><span class="v">{{ row.channel }}</span></div>
      </div>
      <el-empty v-if="tableData.length === 0" description="暂无交易明细"></el-empty>
    </div>

    <div class="pagination">
      <el-pagination
        background layout="total, prev, pager, next" :small="isMobile"
        :current-page="query.pageIndex" :page-size="query.pageSize" :total="pageTotal"
        @current-change="handlePageChange"
      ></el-pagination>
    </div>
  </div>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref, watch} from 'vue';
import {useRoute} from 'vue-router';
import {ElMessage} from 'element-plus';
import {ArrowLeft, Download, Search} from '@element-plus/icons-vue';
import {
  exportTransactions,
  getRiskAlerts,
  getStatement,
  listTransactions,
  saveBlob,
} from '../api/bankStatement';
import type {RiskAlertMark, RiskAlertSummary} from '../api/bankStatement';
import {useIsMobile} from '../composables/useIsMobile';
import {useFillHeight} from '../composables/useFillHeight';
import {useRiskTags} from '../composables/useRiskTags';
import RiskTags from '../components/RiskTags.vue';
import RiskAlertPanel from '../components/RiskAlertPanel.vue';

const route = useRoute();
const id = Number(route.params.id);
const statement = ref<any>(null);
const isMobile = useIsMobile();
// 桌面端：表格高度跟随容器，数据多时表格内部滚动，页面不出现上下滚动条
const tableWrap = ref<HTMLElement | null>(null);
const {height: tableHeight} = useFillHeight(tableWrap);

const query = reactive({
  direction: '',
  dateRange: [] as string[],
  minAmount: '',
  maxAmount: '',
  keyword: '',
  pageIndex: 1,
  pageSize: 50,
});
const tableData = ref<any[]>([]);
const pageTotal = ref(0);
// 户名/对手标签：按当前页出现的名称批量取回
const customerTags = useRiskTags('CUSTOMER');
const counterpartyTags = useRiskTags('COUNTERPARTY');
// 风险警示：当前页行标记 + 整份流水单汇总
const riskSummary = ref<RiskAlertSummary | null>(null);
const riskMarks = ref<Record<string, RiskAlertMark>>({});

/** 方向被业务口径映射过时，悬浮提示数据源原始方向 */
const directionTitle = (row: { rawDirection?: string; direction?: string }) =>
  row?.rawDirection && row.rawDirection !== row.direction
    ? `原始方向：${row.rawDirection}`
    : '';

onMounted(() => {
  if (typeof route.query.keyword === 'string') {
    query.keyword = route.query.keyword;
  }
  getStatement(id).then((res: any) => {
    statement.value = res.data;
    customerTags.load([res.data?.accountHolder]);
  });
  loadData();
});

// 已在本页时，顶部汇总里点「原流水」会带 keyword 跳同路由，需要重新加载
watch(() => route.query.keyword, (val) => {
  const next = typeof val === 'string' ? val : '';
  if (next === query.keyword) {
    return;
  }
  query.keyword = next;
  query.pageIndex = 1;
  loadData();
});

const loadData = () => {
  listTransactions(id, {
    direction: query.direction || undefined,
    transDateFrom: query.dateRange?.[0] || undefined,
    transDateTo: query.dateRange?.[1] || undefined,
    minAmount: query.minAmount || undefined,
    maxAmount: query.maxAmount || undefined,
    keyword: query.keyword || undefined,
    page: query.pageIndex,
    size: query.pageSize,
  }).then((res: any) => {
    tableData.value = res.data.content || [];
    pageTotal.value = res.data.totalElements || 0;
    counterpartyTags.load(tableData.value.map((row) => row.counterpartyName));
    loadRiskAlerts();
  });
};

/** 风险比对：当前页行批量比对 + 整份流水单汇总，一次请求完成 */
const loadRiskAlerts = () => {
  riskMarks.value = {};
  getRiskAlerts({
    scope: 'STATEMENT',
    statementId: id,
    items: tableData.value.map((row) => ({
      key: String(row.id),
      name: row.counterpartyName || undefined,
      account: row.counterpartyAccount || undefined,
      date: row.transDate || undefined,
      direction: row.direction || undefined,
      amount: row.amount ?? undefined,
    })),
  }).then((res: any) => {
    riskMarks.value = res.data?.marks || {};
    riskSummary.value = res.data?.summary || null;
  }).catch(() => {
    riskMarks.value = {};
    riskSummary.value = null;
  });
};

const riskRowClass = ({row}: { row: any }) => {
  const mark = riskMarks.value[row.id];
  if (!mark) {
    return '';
  }
  return mark.matchType === 'NAME_ACCOUNT' ? 'risk-row--both' : 'risk-row--name';
};

const riskCardClass = (row: any) => {
  const mark = riskMarks.value[row.id];
  if (!mark) {
    return '';
  }
  return mark.matchType === 'NAME_ACCOUNT' ? 'txn-card--both' : 'txn-card--name';
};

const riskBadge = (mark: RiskAlertMark) =>
  mark.library === 'FUNDER' ? '资方' : '黑名单';

const handleSearch = () => {
  query.pageIndex = 1;
  loadData();
};
const handlePageChange = (val: number) => {
  query.pageIndex = val;
  loadData();
};

const handleExport = () => {
  exportTransactions(id).then((res: any) => {
    saveBlob(res, `${statement.value?.bankName || '流水'}_明细.csv`);
    ElMessage.success('导出成功');
  }).catch(() => {
    // 错误提示已由请求拦截器统一弹出
  });
};
</script>

<style scoped>
.page {
  background: #fff;
  border-radius: 4px;
  padding: 16px;
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
}
.summary-card { flex: none; background: #f5f7fa; padding: 16px 20px; border-radius: 6px; margin-bottom: 16px; }
.summary-card h3 { margin: 0 0 12px; }
.summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px 24px; }
.summary-grid label { color: #909399; margin-right: 8px; }
.summary-grid .income { color: #67c23a; }
.summary-grid .expense { color: #f56c6c; }
.handle-box { flex: none; margin-bottom: 16px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.handle-box > * { margin: 0; }
.handle-select { width: 120px; }
.handle-input { width: 220px; }
.handle-input-small { width: 110px; }
.table-wrap { flex: 1 1 auto; min-height: 0; }
.table { width: 100%; font-size: 13px; }
.pagination { flex: none; margin-top: 16px; }
.income { color: #67c23a; }
.expense { color: #f56c6c; }

@media (max-width: 768px) {
  .page { padding: 10px 8px; height: auto; min-height: 100%; display: block; }
  .summary-card { padding: 12px; }
  .summary-grid { grid-template-columns: 1fr; gap: 6px; }
  .summary-card h3 { font-size: 16px; }
  .handle-select,
  .handle-input,
  .handle-input-small { width: 100%; }
}

.direction-mapped {
  border-bottom: 1px dashed #c0c4cc;
  cursor: help;
}

/* 风险警示：命中行底色（红=名称+卡号，黄=仅名称/仅卡号） */
:deep(.el-table__body tr.risk-row--both > td.el-table__cell) {
  background: #fef0f0;
}
:deep(.el-table__body tr.risk-row--both:hover > td.el-table__cell) {
  background: #fde2e2;
}
:deep(.el-table__body tr.risk-row--name > td.el-table__cell) {
  background: #fdf6ec;
}
:deep(.el-table__body tr.risk-row--name:hover > td.el-table__cell) {
  background: #faecd8;
}

.risk-flag {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  border: 1px solid transparent;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
  vertical-align: middle;
  cursor: help;
}
.risk-flag--both {
  color: #fff;
  background: #f56c6c;
  border-color: #f56c6c;
}
.risk-flag--name {
  color: #e6a23c;
  background: #fdf6ec;
  border-color: #faecd8;
}
.risk-tip {
  max-width: 320px;
  line-height: 1.6;
}
.risk-tip-title {
  font-weight: 600;
  margin-bottom: 2px;
}
.risk-tip-sub {
  font-size: 12px;
  opacity: 0.85;
}

.txn-card--both {
  border-color: #f56c6c;
  box-shadow: inset 0 0 0 1px #f56c6c;
}
.txn-card--name {
  border-color: #e6a23c;
  box-shadow: inset 0 0 0 1px #e6a23c;
}
.card-risk {
  margin: 4px 0 8px;
  padding: 6px 8px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.6;
}
.card-risk--both {
  color: #f56c6c;
  background: #fef0f0;
}
.card-risk--name {
  color: #e6a23c;
  background: #fdf6ec;
}

/* 移动端卡片式明细 */
.card-list { display: flex; flex-direction: column; gap: 10px; }
.txn-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 6px;
  margin-bottom: 6px;
  border-bottom: 1px solid #f0f0f0;
}
.card-date { font-size: 13px; color: #606266; }
.card-amount { font-size: 20px; font-weight: 600; margin-bottom: 6px; }
.card-row { display: flex; font-size: 13px; line-height: 22px; }
.card-row .k { flex: 0 0 40px; color: #909399; }
.card-row .v { flex: 1; color: #303133; word-break: break-all; }
</style>
