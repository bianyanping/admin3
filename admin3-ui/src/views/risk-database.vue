<template>
  <div class="page">
    <div class="risk-head">
      <div class="risk-head-main">
        <span class="risk-title">风险数据库</span>
        <span class="card-sub" v-if="summary">
          交易对手主数据由 {{ summary.counterpartyTotal }} 条对手沉淀 · 消费型剔除
          {{ summary.consumerCount }} 条 · 客户 {{ summary.customerCount }} 户
          <template v-if="summary.syncTime"> · 沉淀时间 {{ summary.syncTime }}</template>
        </span>
      </div>
      <el-button :icon="RefreshRight" :loading="syncing" @click="handleSync">重建对手库</el-button>
    </div>

    <el-tabs v-model="activeTab" class="risk-tabs" @tab-change="onTabChange">
      <!-- ==================== ① 资方数据库 ==================== -->
      <el-tab-pane label="资方数据库" name="funder">
        <div class="handle-box">
          <el-input
            v-model="funderQuery.keyword"
            placeholder="对手名称 / 卡号"
            class="handle-input"
            clearable
            @keyup.enter="searchFunders"
          ></el-input>
          <el-button type="primary" :icon="Search" @click="searchFunders">搜索</el-button>
          <el-button type="warning" :icon="Pointer" @click="activeTab = 'counterparty'">去交易对手库标记</el-button>
          <span class="card-sub">共 {{ funderTotal }} 个民间资方（手工标记）</span>
        </div>

        <el-table :data="funderRows" border size="small" class="table" v-loading="funderLoading">
          <el-table-column label="对手名称" min-width="200">
            <template #default="{ row }">
              <span class="name-line">{{ row.displayName }}</span>
              <RiskTags :tags="row.tags" />
            </template>
          </el-table-column>
          <el-table-column label="卡号 / 账号" min-width="190">
            <template #default="{ row }">
              <span class="mono">{{ row.accountNos || '--' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="txnCount" label="笔数" width="80" align="right"></el-table-column>
          <el-table-column label="转入 / 转出" min-width="150" align="right">
            <template #default="{ row }">
              <div class="income">{{ formatMoney(row.incomeTotal) }}（{{ row.incomeCount }} 笔）</div>
              <div class="expense">{{ formatMoney(row.expenseTotal) }}（{{ row.expenseCount }} 笔）</div>
            </template>
          </el-table-column>
          <el-table-column label="最近往来" width="105">
            <template #default="{ row }">{{ row.lastDate || '--' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center" fixed="right">
            <template #default="{ row }">
              <el-button text type="danger" size="small" @click="handleUnmarkFunder(row)">解除资方</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty
          v-if="!funderLoading && funderRows.length === 0"
          description="暂无民间资方；可在「交易对手数据库」页对任意对手打「民间资方」标签"
        ></el-empty>

        <div class="pagination">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :current-page="funderQuery.page"
            :page-size="funderQuery.size"
            :total="funderTotal"
            @current-change="onFunderPage"
          ></el-pagination>
        </div>
      </el-tab-pane>

      <!-- ==================== ② 黑名单数据库 ==================== -->
      <el-tab-pane label="黑名单数据库" name="blacklist">
        <div class="handle-box">
          <el-input
            v-model="customerKeyword"
            placeholder="客户 / 户主名称"
            class="handle-input"
            clearable
            @keyup.enter="loadCustomers"
          ></el-input>
          <el-button type="primary" :icon="Search" @click="loadCustomers">搜索</el-button>
          <span class="card-sub">
            共 {{ customers.length }} 个客户 · 黑名单 {{ blacklistCount }} 个 ·
            主体是客户/户主，不是交易对手
          </span>
        </div>

        <el-table :data="customers" border size="small" class="table" v-loading="customerLoading">
          <el-table-column label="客户名" min-width="200">
            <template #default="{ row }">
              <span class="name-line">{{ row.name }}</span>
              <RiskTags :tags="row.tags" />
            </template>
          </el-table-column>
          <el-table-column prop="accountCount" label="账户数" width="90" align="right"></el-table-column>
          <el-table-column prop="statementCount" label="流水单数" width="100" align="right"></el-table-column>
          <el-table-column prop="transactionCount" label="流水笔数" width="100" align="right"></el-table-column>
          <el-table-column label="账期" min-width="190">
            <template #default="{ row }">{{ row.firstDate || '--' }} ~ {{ row.lastDate || '--' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="!hasTag(row.tags, 'BLACKLIST_CUSTOMER')"
                text
                type="danger"
                size="small"
                @click="markCustomer(row)"
              >标记黑名单</el-button>
              <el-button v-else text type="primary" size="small" @click="unmarkCustomer(row)">解除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!customerLoading && customers.length === 0" description="暂无客户数据"></el-empty>
      </el-tab-pane>

      <!-- ==================== ③ 交易对手数据库 ==================== -->
      <el-tab-pane label="交易对手数据库" name="counterparty">
        <el-row :gutter="12" class="risk-stats" v-if="summary">
          <el-col :xs="12" :sm="12" :md="6">
            <div class="stat-card">
              <div class="stat-label">沉淀对手</div>
              <div class="stat-value">{{ summary.counterpartyTotal }}</div>
              <div class="stat-sub">名称 + 卡号/账号，按名称归并</div>
            </div>
          </el-col>
          <el-col :xs="12" :sm="12" :md="6">
            <div class="stat-card">
              <div class="stat-label">保留对手</div>
              <div class="stat-value income">{{ summary.keptCount }}</div>
              <div class="stat-sub">个人 / 公司类名称</div>
            </div>
          </el-col>
          <el-col :xs="12" :sm="12" :md="6">
            <div class="stat-card">
              <div class="stat-label">消费型已剔除</div>
              <div class="stat-value expense">{{ summary.consumerCount }}</div>
              <div class="stat-sub">涉及 {{ summary.consumerTxnCount }} 笔流水</div>
            </div>
          </el-col>
          <el-col :xs="12" :sm="12" :md="6">
            <div class="stat-card">
              <div class="stat-label">剔除规则</div>
              <div class="stat-value">{{ summary.ruleCount }}</div>
              <div class="stat-sub">关键词 / 名单，可增删启停</div>
            </div>
          </el-col>
        </el-row>

        <div class="exclude-bar" v-if="summary">
          <div class="exclude-line">
            <span class="exclude-label">剔除规则</span>
            <el-tag
              v-for="rule in summary.rules.slice(0, 8)"
              :key="rule.keyword + rule.matchType"
              size="small"
              type="info"
              class="rule-chip"
              disable-transitions
            >{{ rule.keyword }}<span class="chip-count">{{ rule.count }}</span></el-tag>
            <span class="muted" v-if="summary.rules.length === 0">未配置剔除规则</span>
          </div>
          <div class="exclude-line">
            <span class="exclude-label">剔除示例</span>
            <span class="card-sub">{{ summary.consumerSamples.join('、') || '--' }}</span>
            <el-button text type="primary" size="small" class="rule-config-btn" @click="openRuleDrawer">
              剔除规则配置
            </el-button>
          </div>
        </div>

        <div class="handle-box">
          <el-input
            v-model="cpQuery.keyword"
            placeholder="对手名称 / 卡号"
            class="handle-input"
            clearable
            @keyup.enter="searchCounterparties"
          ></el-input>
          <el-select v-model="cpQuery.consumer" class="handle-select" @change="searchCounterparties">
            <el-option label="仅看保留对手" value="EXCLUDE"></el-option>
            <el-option label="全部（含消费型）" value="INCLUDE"></el-option>
            <el-option label="仅看消费型" value="ONLY"></el-option>
          </el-select>
          <el-button type="primary" :icon="Search" @click="searchCounterparties">搜索</el-button>
          <span class="card-sub">共 {{ cpTotal }} 条 · 服务端分页，按名称/卡号搜索</span>
        </div>

        <el-table
          :data="cpRows"
          border
          size="small"
          class="table"
          v-loading="cpLoading"
          @sort-change="onSortChange"
        >
          <el-table-column label="对手名称" min-width="210">
            <template #default="{ row }">
              <span class="name-line">{{ row.displayName }}</span>
              <RiskTags :tags="row.tags" />
              <el-tag v-if="row.consumer" size="small" type="info" class="consumer-tag" disable-transitions>
                消费型·{{ row.consumerRules }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="卡号 / 账号" min-width="180">
            <template #default="{ row }">
              <span class="mono">{{ row.accountNos || '--' }}</span>
              <div class="sub" v-if="row.accountCount > 1">{{ row.accountCount }} 个账号</div>
            </template>
          </el-table-column>
          <el-table-column prop="txnCount" label="笔数" width="82" align="right" sortable="custom"></el-table-column>
          <el-table-column prop="incomeTotal" label="转入" width="130" align="right" sortable="custom">
            <template #default="{ row }">
              <div class="income">{{ formatMoney(row.incomeTotal) }}</div>
              <div class="sub">{{ row.incomeCount }} 笔</div>
            </template>
          </el-table-column>
          <el-table-column prop="expenseTotal" label="转出" width="130" align="right" sortable="custom">
            <template #default="{ row }">
              <div class="expense">{{ formatMoney(row.expenseTotal) }}</div>
              <div class="sub">{{ row.expenseCount }} 笔</div>
            </template>
          </el-table-column>
          <el-table-column prop="lastDate" label="最近往来" width="110" sortable="custom">
            <template #default="{ row }">{{ row.lastDate || '--' }}</template>
          </el-table-column>
          <el-table-column label="标签操作" min-width="190" align="center" fixed="right">
            <template #default="{ row }">
              <el-button
                text
                size="small"
                :type="hasTag(row.tags, 'FUNDER') ? 'warning' : 'primary'"
                @click="toggleCpTag(row, 'FUNDER')"
              >{{ hasTag(row.tags, 'FUNDER') ? '取消资方' : '标记资方' }}</el-button>
              <el-button
                text
                size="small"
                :type="hasTag(row.tags, 'BLACKLIST_CP') ? 'danger' : 'primary'"
                @click="toggleCpTag(row, 'BLACKLIST_CP')"
              >{{ hasTag(row.tags, 'BLACKLIST_CP') ? '取消黑名单' : '黑名单' }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!cpLoading && cpRows.length === 0" description="暂无匹配的交易对手"></el-empty>

        <div class="pagination">
          <el-pagination
            background
            layout="total, prev, pager, next"
            :current-page="cpQuery.page"
            :page-size="cpQuery.size"
            :total="cpTotal"
            @current-change="onCpPage"
          ></el-pagination>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 剔除规则配置 -->
    <el-drawer v-model="ruleDrawer" title="消费型对手剔除规则" size="680px">
      <div class="rule-drawer">
        <div class="handle-box">
          <el-button type="primary" size="small" :icon="Plus" @click="openRuleForm(null)">新增规则</el-button>
          <span class="card-sub">
            包含=名称含关键词即剔除；完全一致=仅剔除同名对手（如「京东」保留「京东物流运输有限公司」）
          </span>
        </div>
        <el-table :data="rules" border size="small" class="table">
          <el-table-column prop="keyword" label="关键词" width="120" show-overflow-tooltip></el-table-column>
          <el-table-column label="匹配方式" width="90" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.matchType === 'EXACT' ? 'warning' : 'info'" disable-transitions>
                {{ row.matchType === 'EXACT' ? '完全一致' : '包含' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="category" label="分类" width="100"></el-table-column>
          <el-table-column label="状态" width="70" align="center">
            <template #default="{ row }">
              <el-switch :model-value="row.enabled" @change="(val: boolean) => toggleRule(row, val)"></el-switch>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" align="center">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openRuleForm(row)">编辑</el-button>
              <el-button v-if="!row.builtin" text type="danger" size="small" @click="removeRule(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-drawer>

    <el-dialog v-model="ruleDialog" :title="ruleForm.id ? '编辑剔除规则' : '新增剔除规则'" width="480px">
      <el-form :model="ruleForm" label-width="90px">
        <el-form-item label="关键词" required>
          <el-input v-model="ruleForm.keyword" maxlength="128" placeholder="如：抖音、美团、京东"></el-input>
        </el-form-item>
        <el-form-item label="匹配方式">
          <el-select v-model="ruleForm.matchType" style="width: 100%">
            <el-option label="包含（推荐）" value="CONTAINS"></el-option>
            <el-option label="完全一致" value="EXACT"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="ruleForm.category" maxlength="64" placeholder="如：电商 / 外卖 / 支付"></el-input>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled"></el-switch>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleDialog = false">取消</el-button>
        <el-button type="primary" :loading="ruleSaving" @click="saveRule">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, ref} from 'vue';
import {ElMessage, ElMessageBox} from 'element-plus';
import {Plus, Pointer, RefreshRight, Search} from '@element-plus/icons-vue';
import RiskTags from '../components/RiskTags.vue';
import type {RiskTagItem} from '../composables/useRiskTags';
import {
  createExclusionRule,
  deleteExclusionRule,
  getRiskSummary,
  linkRiskTag,
  listExclusionRules,
  listRiskCounterparties,
  listRiskCustomers,
  setExclusionRuleEnabled,
  syncRiskCounterparties,
  unlinkRiskTag,
  updateExclusionRule,
} from '../api/risk';

interface RuleStat {
  keyword: string;
  matchType: string;
  count: number;
  txnCount: number;
}

interface Summary {
  counterpartyTotal: number;
  consumerCount: number;
  keptCount: number;
  consumerTxnCount: number;
  customerCount: number;
  blacklistCustomerCount: number;
  funderCount: number;
  ruleCount: number;
  syncTime: string;
  rules: RuleStat[];
  consumerSamples: string[];
}

interface CounterpartyRow {
  id: number;
  nameKey: string;
  displayName: string;
  accountNos: string;
  accountCount: number;
  txnCount: number;
  incomeCount: number;
  incomeTotal: number;
  expenseCount: number;
  expenseTotal: number;
  holderCount: number;
  firstDate: string;
  lastDate: string;
  consumer: boolean;
  consumerRules: string;
  tags: RiskTagItem[];
}

interface CustomerRow {
  nameKey: string;
  name: string;
  accountCount: number;
  statementCount: number;
  transactionCount: number;
  creditReportCount: number;
  totalIncome: number;
  totalExpense: number;
  firstDate: string;
  lastDate: string;
  tags: RiskTagItem[];
}

interface ExclusionRule {
  id: number;
  keyword: string;
  matchType: string;
  category: string;
  enabled: boolean;
  builtin: boolean;
  remark: string;
}

const activeTab = ref('funder');
const summary = ref<Summary | null>(null);
const syncing = ref(false);

const funderQuery = ref({keyword: '', page: 1, size: 20});
const funderRows = ref<CounterpartyRow[]>([]);
const funderTotal = ref(0);
const funderLoading = ref(false);

const customerKeyword = ref('');
const customers = ref<CustomerRow[]>([]);
const customerLoading = ref(false);

const cpQuery = ref({
  keyword: '',
  consumer: 'EXCLUDE',
  sortBy: 'txnCount',
  sortDir: 'desc',
  page: 1,
  size: 20,
});
const cpRows = ref<CounterpartyRow[]>([]);
const cpTotal = ref(0);
const cpLoading = ref(false);

const ruleDrawer = ref(false);
const rules = ref<ExclusionRule[]>([]);
const ruleDialog = ref(false);
const ruleSaving = ref(false);
const ruleForm = ref<any>({id: null, keyword: '', matchType: 'CONTAINS', category: '', enabled: true});

const formatMoney = (value: any) =>
  Number(value || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});

const hasTag = (tags: RiskTagItem[] | undefined, code: string) =>
  (tags || []).some((tag) => tag.code === code);

const blacklistCount = computed(() =>
  customers.value.filter((c) => hasTag(c.tags, 'BLACKLIST_CUSTOMER')).length);

const loadSummary = () => {
  getRiskSummary().then((res: any) => {
    summary.value = res.data;
  });
};

const loadFunders = () => {
  funderLoading.value = true;
  listRiskCounterparties({
    keyword: funderQuery.value.keyword || undefined,
    consumer: 'INCLUDE',
    tagCode: 'FUNDER',
    page: funderQuery.value.page,
    size: funderQuery.value.size,
    sortBy: 'lastDate',
    sortDir: 'desc',
  }).then((res: any) => {
    funderRows.value = res.data?.content || [];
    funderTotal.value = res.data?.totalElements || 0;
  }).finally(() => {
    funderLoading.value = false;
  });
};

const loadCustomers = () => {
  customerLoading.value = true;
  listRiskCustomers({keyword: customerKeyword.value || undefined})
    .then((res: any) => {
      customers.value = res.data || [];
    })
    .finally(() => {
      customerLoading.value = false;
    });
};

const loadCounterparties = () => {
  cpLoading.value = true;
  listRiskCounterparties({
    keyword: cpQuery.value.keyword || undefined,
    consumer: cpQuery.value.consumer,
    page: cpQuery.value.page,
    size: cpQuery.value.size,
    sortBy: cpQuery.value.sortBy,
    sortDir: cpQuery.value.sortDir,
  }).then((res: any) => {
    cpRows.value = res.data?.content || [];
    cpTotal.value = res.data?.totalElements || 0;
  }).finally(() => {
    cpLoading.value = false;
  });
};

const onTabChange = () => {
  if (activeTab.value === 'funder') {
    loadFunders();
  } else if (activeTab.value === 'blacklist') {
    loadCustomers();
  } else {
    loadCounterparties();
  }
  loadSummary();
};

const searchFunders = () => {
  funderQuery.value.page = 1;
  loadFunders();
};

const onFunderPage = (page: number) => {
  funderQuery.value.page = page;
  loadFunders();
};

const searchCounterparties = () => {
  cpQuery.value.page = 1;
  loadCounterparties();
};

const onCpPage = (page: number) => {
  cpQuery.value.page = page;
  loadCounterparties();
};

const onSortChange = ({prop, order}: { prop: string; order: string | null }) => {
  cpQuery.value.sortBy = prop || 'txnCount';
  cpQuery.value.sortDir = order === 'ascending' ? 'asc' : 'desc';
  cpQuery.value.page = 1;
  loadCounterparties();
};

const handleSync = () => {
  syncing.value = true;
  syncRiskCounterparties()
    .then(() => {
      ElMessage.success('交易对手库已重建');
      loadSummary();
      onTabChange();
    })
    .finally(() => {
      syncing.value = false;
    });
};

const handleUnmarkFunder = (row: CounterpartyRow) => {
  ElMessageBox.confirm(`确认取消「${row.displayName}」的民间资方标记？`, '取消标记', {type: 'warning'})
    .then(() => unlinkRiskTag('COUNTERPARTY', row.displayName, 'FUNDER'))
    .then(() => {
      ElMessage.success('已取消资方标记');
      loadFunders();
      loadSummary();
    })
    .catch(() => {
    });
};

const toggleCpTag = (row: CounterpartyRow, tagCode: string) => {
  const marked = hasTag(row.tags, tagCode);
  const action = marked
    ? unlinkRiskTag('COUNTERPARTY', row.displayName, tagCode)
    : linkRiskTag('COUNTERPARTY', row.displayName, tagCode);
  action
    .then((res: any) => {
      if (marked) {
        row.tags = (row.tags || []).filter((tag) => tag.code !== tagCode);
      } else if (res?.data) {
        row.tags = res.data;
      }
      ElMessage.success(marked ? '已取消标记' : '已标记');
      loadSummary();
    })
    .catch(() => {
    });
};

const markCustomer = (row: CustomerRow) => {
  linkRiskTag('CUSTOMER', row.name, 'BLACKLIST_CUSTOMER')
    .then((res: any) => {
      row.tags = res.data || row.tags;
      ElMessage.success('已标记黑名单客户');
      loadSummary();
    })
    .catch(() => {
    });
};

const unmarkCustomer = (row: CustomerRow) => {
  ElMessageBox.confirm(`确认解除「${row.name}」的黑名单标记？`, '解除黑名单', {type: 'warning'})
    .then(() => unlinkRiskTag('CUSTOMER', row.name, 'BLACKLIST_CUSTOMER'))
    .then(() => {
      row.tags = (row.tags || []).filter((tag) => tag.code !== 'BLACKLIST_CUSTOMER');
      ElMessage.success('已解除黑名单');
      loadSummary();
    })
    .catch(() => {
    });
};

// ==================== 剔除规则 ====================

const loadRules = () => {
  listExclusionRules().then((res: any) => {
    rules.value = res.data || [];
  });
};

const openRuleDrawer = () => {
  ruleDrawer.value = true;
  loadRules();
};

const openRuleForm = (row: ExclusionRule | null) => {
  ruleForm.value = row
    ? {...row}
    : {id: null, keyword: '', matchType: 'CONTAINS', category: '', enabled: true};
  ruleDialog.value = true;
};

const saveRule = () => {
  if (!ruleForm.value.keyword.trim()) {
    ElMessage.warning('请输入关键词');
    return;
  }
  ruleSaving.value = true;
  const payload = {...ruleForm.value};
  const request = payload.id
    ? updateExclusionRule(payload.id, payload)
    : createExclusionRule(payload);
  request
    .then(() => {
      ElMessage.success('已保存，对手库将按新规则重建');
      ruleDialog.value = false;
      loadRules();
      loadSummary();
      loadCounterparties();
    })
    .finally(() => {
      ruleSaving.value = false;
    });
};

const toggleRule = (row: ExclusionRule, enabled: boolean) => {
  setExclusionRuleEnabled(row.id, enabled)
    .then(() => {
      row.enabled = enabled;
      ElMessage.success(enabled ? '已启用' : '已停用，对手库将按新规则重建');
      loadSummary();
      loadCounterparties();
    })
    .catch(() => {
    });
};

const removeRule = (row: ExclusionRule) => {
  ElMessageBox.confirm(`确认删除剔除规则「${row.keyword}」？`, '删除规则', {type: 'warning'})
    .then(() => deleteExclusionRule(row.id))
    .then(() => {
      ElMessage.success('已删除');
      loadRules();
      loadSummary();
      loadCounterparties();
    })
    .catch(() => {
    });
};

onMounted(() => {
  loadSummary();
  loadFunders();
});
</script>

<style scoped>
.page {
  background: #fff;
  border-radius: 4px;
  padding: 16px;
  min-height: 100%;
  box-sizing: border-box;
}

.risk-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.risk-head-main {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.risk-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.risk-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}

.handle-box {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.handle-box > * {
  margin: 0;
}

.handle-input {
  width: 220px;
}

.handle-select {
  width: 160px;
}

.card-sub {
  color: #909399;
  font-size: 12px;
}

.risk-stats {
  margin: 4px 0 10px;
}

.stat-card {
  background: #f5f7fa;
  border-radius: 6px;
  padding: 10px 14px;
}

.stat-label {
  color: #909399;
  font-size: 12px;
}

.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: #303133;
  line-height: 1.4;
}

.stat-sub {
  color: #909399;
  font-size: 12px;
}

.income {
  color: #67c23a;
}

.expense {
  color: #f56c6c;
}

.muted {
  color: #c0c4cc;
}

.exclude-bar {
  background: #fafafa;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
}

.exclude-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  line-height: 26px;
}

.exclude-label {
  color: #606266;
  font-size: 12px;
  font-weight: 600;
}

.rule-chip {
  margin: 0;
}

.chip-count {
  margin-left: 4px;
  opacity: .7;
}

.rule-config-btn {
  margin-left: auto;
}

.name-line {
  margin-right: 6px;
  font-weight: 500;
  color: #303133;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  color: #606266;
  word-break: break-all;
}

.sub {
  color: #909399;
  font-size: 12px;
}

.consumer-tag {
  margin-left: 6px;
}

.rule-drawer :deep(.handle-box) {
  margin-bottom: 12px;
}

@media (max-width: 768px) {
  .page {
    padding: 10px 8px;
  }

  .risk-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .handle-input,
  .handle-select {
    width: 100%;
  }
}
</style>
