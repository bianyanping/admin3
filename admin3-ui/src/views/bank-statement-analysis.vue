<template>
  <div class="page">
    <div class="toolbar">
      <el-select
        v-model="holder"
        filterable
        clearable
        placeholder="选择户名"
        class="holder-select"
        @change="onHolderChange"
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
      <el-button type="primary" :icon="Search" @click="onHolderChange">查询</el-button>
      <el-button :icon="Refresh" @click="refreshAll">刷新</el-button>
    </div>

    <RiskAlertPanel :summary="riskAlerts" />

    <div v-if="analysis" class="analysis-body">
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

      <div class="card" ref="overviewCard">
        <div class="card-head">
          <span class="card-title">账户概览</span>
          <span class="card-sub">
            <template v-if="holder">
              <span>当前客户：{{ holder }}</span>
              <RiskTags :tags="customerTags.tagsFor(holder)" />
              <span> · </span>
            </template>
            账期 {{ analysis.startDate || '--' }} ~ {{ analysis.endDate || '--' }} ·
            日均余额 = Σ(日末余额 × 持续天数) / 统计天数
          </span>
        </div>

        <el-table v-if="overviewTableAllowed" :data="sortedAccounts" border size="small" class="table overview-table">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="overview-expand">
                <div class="expand-block">
                  <div class="expand-title">计算口径</div>
                  <div class="expand-text">{{ row.overview?.rangeBasis || '无余额数据' }}</div>
                  <div class="expand-text" v-if="row.overview?.balanceAvailable">
                    统计区间 {{ row.overview.statStart }} ~ {{ row.overview.statEnd }}，共
                    {{ row.overview.statDays }} 天
                  </div>
                  <div class="expand-text">{{ row.overview?.note }}</div>
                </div>
                <div class="expand-block">
                  <div class="expand-title">
                    结息明细
                    <span v-if="row.overview && row.overview.interestCount > 0">
                      （收入 {{ row.overview.interestCount }} 笔 / {{ formatMoney(row.overview.interestTotal) }}<template
                        v-if="row.overview.interestExpenseCount > 0">，支出 {{ row.overview.interestExpenseCount }} 笔 / {{ formatMoney(row.overview.interestExpenseTotal) }}</template>）
                    </span>
                  </div>
                  <template v-if="row.overview && (row.overview.interests.length || row.overview.interestExpenses.length)">
                    <table class="interest-table">
                      <colgroup>
                        <col style="width: 92px" />
                        <col style="width: 42px" />
                        <col style="width: 20%" />
                        <col />
                        <col style="width: 96px" />
                      </colgroup>
                      <thead>
                        <tr>
                          <th>日期</th>
                          <th>方向</th>
                          <th>对方</th>
                          <th>摘要</th>
                          <th class="ta-r">金额</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr v-for="item in row.overview.interests" :key="item.transactionId">
                          <td>{{ item.transDate }}</td>
                          <td>{{ item.direction || '入' }}</td>
                          <td>
                          {{ item.counterpartyName || '--' }}
                          <span
                            v-if="tagText(counterpartyTags.tagsFor(item.counterpartyName))"
                            class="risk-chip"
                            :class="tagClass(counterpartyTags.tagsFor(item.counterpartyName))"
                          >{{ tagText(counterpartyTags.tagsFor(item.counterpartyName)) }}</span>
                        </td>
                          <td>{{ item.summary || '--' }}</td>
                          <td class="ta-r income">+{{ formatMoney(item.amount) }}</td>
                        </tr>
                        <tr v-for="item in row.overview.interestExpenses" :key="'expense-' + item.transactionId">
                          <td>{{ item.transDate }}</td>
                          <td>{{ item.direction || '出' }}</td>
                          <td>
                          {{ item.counterpartyName || '--' }}
                          <span
                            v-if="tagText(counterpartyTags.tagsFor(item.counterpartyName))"
                            class="risk-chip"
                            :class="tagClass(counterpartyTags.tagsFor(item.counterpartyName))"
                          >{{ tagText(counterpartyTags.tagsFor(item.counterpartyName)) }}</span>
                        </td>
                          <td>{{ item.summary || '--' }}</td>
                          <td class="ta-r expense">{{ formatMoney(item.amount) }}</td>
                        </tr>
                      </tbody>
                    </table>
                  </template>
                  <div v-else class="expand-text">无结息</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="账户" min-width="150">
            <template #default="{ row }">
              <div class="acct-line">
                <span class="acct-name">{{ row.bankName || '未知银行' }}</span>
                <el-button text type="primary" size="small" class="ic-action" @click="goStatement(row)">查看明细</el-button>
              </div>
              <div class="sub">{{ row.accountNo }} · {{ row.transactionCount }} 笔</div>
            </template>
          </el-table-column>
          <el-table-column label="账期 / 统计区间" min-width="130">
            <template #default="{ row }">
              <div>{{ row.startDate || '--' }} ~ {{ row.endDate || '--' }}</div>
              <div class="sub" v-if="row.overview?.balanceAvailable">
                计 {{ row.overview.statDays }} 天：{{ row.overview.statStart }} ~ {{ row.overview.statEnd }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="期初 / 期末余额" min-width="105" align="right">
            <template #default="{ row }">
              <div>{{ moneyOrDash(row.overview?.openingBalance) }}</div>
              <div class="sub">{{ moneyOrDash(row.overview?.closingBalance) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="入账 / 出款合计" min-width="118" align="right">
            <template #default="{ row }">
              <div class="income">{{ formatMoney(row.totalIncome) }}</div>
              <div class="expense">{{ formatMoney(row.totalExpense) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="日均余额" min-width="100" align="right">
            <template #default="{ row }">
              <span v-if="row.overview?.balanceAvailable">{{ formatMoney(row.overview.avgDailyBalance) }}</span>
              <span v-else class="muted">无余额数据</span>
            </template>
          </el-table-column>
          <el-table-column label="最高余额" min-width="115" align="right">
            <template #default="{ row }">
              <template v-if="row.overview?.balanceAvailable">
                <div class="income">{{ formatMoney(row.overview.maxBalance) }}</div>
                <div class="sub">{{ row.overview.maxBalanceDate }}</div>
              </template>
              <span v-else class="muted">无余额数据</span>
            </template>
          </el-table-column>
          <el-table-column label="最高余额对应交易" min-width="150">
            <template #default="{ row }">
              <template v-if="row.overview?.balanceAvailable">
                <template v-if="row.overview.maxBalanceSource === 'TRANSACTION' && row.overview.maxBalanceTxn">
                  <div>
                    {{ row.overview.maxBalanceTxn.counterpartyName || '--' }}
                    <span
                      v-if="tagText(counterpartyTags.tagsFor(row.overview.maxBalanceTxn.counterpartyName))"
                      class="risk-chip"
                      :class="tagClass(counterpartyTags.tagsFor(row.overview.maxBalanceTxn.counterpartyName))"
                    >{{ tagText(counterpartyTags.tagsFor(row.overview.maxBalanceTxn.counterpartyName)) }}</span>
                    <span class="sub"
                          :class="row.overview.maxBalanceTxn.direction === '入' ? 'income' : 'expense'">
                      {{ row.overview.maxBalanceTxn.direction === '入' ? '入账' : (row.overview.maxBalanceTxn.direction || '') }}
                      {{ formatMoney(row.overview.maxBalanceTxn.amount) }}
                    </span>
                  </div>
                  <div class="sub">{{ row.overview.maxBalanceTxn.summary || '--' }}</div>
                  <div class="sub" v-if="row.overview.maxBalanceTxn.remark">
                    备注：{{ row.overview.maxBalanceTxn.remark }}
                  </div>
                </template>
                <span v-else class="muted">来自期初余额，无对应交易</span>
              </template>
              <span v-else class="muted">--</span>
            </template>
          </el-table-column>
          <el-table-column label="结息" min-width="110">
            <template #default="{ row }">
              <template v-if="row.overview && (row.overview.interestCount > 0 || row.overview.interestExpenseCount > 0)">
                <div v-if="row.overview.interestCount > 0">
                  {{ row.overview.interestCount }} 笔
                  <span class="income">{{ formatMoney(row.overview.interestTotal) }}</span>
                </div>
                <div class="sub expense" v-if="row.overview.interestExpenseCount > 0">
                  支出 {{ row.overview.interestExpenseCount }} 笔 {{ formatMoney(row.overview.interestExpenseTotal) }}
                </div>
              </template>
              <span v-else>无结息</span>
            </template>
          </el-table-column>
        </el-table>

        <div v-else class="card-list">
          <div v-for="row in sortedAccounts" :key="row.id" class="info-card">
            <div class="ic-head">
              <div class="ic-title-wrap">
                <span class="ic-title">{{ row.bankName || '未知银行' }}</span>
                <span class="ic-sub">{{ row.accountNo }} · {{ row.transactionCount }} 笔</span>
              </div>
              <el-button text type="primary" size="small" class="ic-action" @click="goStatement(row)">查看明细</el-button>
            </div>
            <div class="kv-grid">
              <div class="kv">
                <span class="k">账期</span>
                <span class="v">{{ row.startDate || '--' }} ~ {{ row.endDate || '--' }}</span>
              </div>
              <div class="kv">
                <span class="k">统计天数</span>
                <span class="v">{{ row.overview?.balanceAvailable ? row.overview.statDays + ' 天' : '无余额数据' }}</span>
              </div>
              <div class="kv">
                <span class="k">期初 / 期末</span>
                <span class="v">{{ moneyOrDash(row.overview?.openingBalance) }} / {{ moneyOrDash(row.overview?.closingBalance) }}</span>
              </div>
              <div class="kv">
                <span class="k">入账</span>
                <span class="v income">{{ formatMoney(row.totalIncome) }}</span>
              </div>
              <div class="kv">
                <span class="k">出款</span>
                <span class="v expense">{{ formatMoney(row.totalExpense) }}</span>
              </div>
              <div class="kv kv-strong">
                <span class="k">日均余额</span>
                <span class="v income">{{ row.overview?.balanceAvailable ? formatMoney(row.overview.avgDailyBalance) : '无余额数据' }}</span>
              </div>
              <div class="kv kv-strong">
                <span class="k">最高余额</span>
                <span class="v">{{ row.overview?.balanceAvailable ? formatMoney(row.overview.maxBalance) + '（' + row.overview.maxBalanceDate + '）' : '无余额数据' }}</span>
              </div>
              <div class="kv" v-if="row.overview?.balanceAvailable">
                <span class="k">最高对应</span>
                <span class="v">
                  {{ row.overview.maxBalanceSource === 'TRANSACTION' && row.overview.maxBalanceTxn
                    ? (row.overview.maxBalanceTxn.counterpartyName || '--')
                    : '来自期初余额' }}
                  <span
                    v-if="row.overview.maxBalanceSource === 'TRANSACTION' && row.overview.maxBalanceTxn && tagText(counterpartyTags.tagsFor(row.overview.maxBalanceTxn.counterpartyName))"
                    class="risk-chip"
                    :class="tagClass(counterpartyTags.tagsFor(row.overview.maxBalanceTxn.counterpartyName))"
                  >{{ tagText(counterpartyTags.tagsFor(row.overview.maxBalanceTxn.counterpartyName)) }}</span>
                  <template v-if="row.overview.maxBalanceSource === 'TRANSACTION' && row.overview.maxBalanceTxn">
                    / {{ row.overview.maxBalanceTxn.summary || '--' }}
                  </template>
                </span>
              </div>
            </div>
            <div class="ic-interest">
              <span class="k">结息</span>
              <span class="v">
                <template v-if="row.overview && (row.overview.interestCount > 0 || row.overview.interestExpenseCount > 0)">
                  <template v-if="row.overview.interestCount > 0">
                    收入 {{ row.overview.interestCount }} 笔 / {{ formatMoney(row.overview.interestTotal) }}
                  </template>
                  <template v-if="row.overview.interestExpenseCount > 0">
                    <span class="expense">　支出 {{ row.overview.interestExpenseCount }} 笔 / {{ formatMoney(row.overview.interestExpenseTotal) }}</span>
                  </template>
                </template>
                <template v-else>无结息</template>
              </span>
              <el-button
                v-if="row.overview && (row.overview.interests.length || row.overview.interestExpenses.length)"
                text
                type="primary"
                size="small"
                @click="toggleInterest(row.id)"
              >{{ expandedInterest.has(row.id) ? '收起逐笔' : '逐笔明细' }}</el-button>
            </div>
            <table class="interest-table" v-if="row.overview && expandedInterest.has(row.id)">
              <colgroup>
                <col style="width: 92px" />
                <col style="width: 42px" />
                <col style="width: 20%" />
                <col />
                <col style="width: 96px" />
              </colgroup>
              <thead>
                <tr>
                  <th>日期</th>
                  <th>方向</th>
                  <th>对方</th>
                  <th>摘要</th>
                  <th class="ta-r">金额</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in row.overview.interests" :key="item.transactionId">
                  <td>{{ item.transDate }}</td>
                  <td>{{ item.direction || '入' }}</td>
                  <td>{{ item.counterpartyName || '--' }}</td>
                  <td>{{ item.summary || '--' }}</td>
                  <td class="ta-r income">+{{ formatMoney(item.amount) }}</td>
                </tr>
                <tr v-for="item in row.overview.interestExpenses" :key="'expense-' + item.transactionId">
                  <td>{{ item.transDate }}</td>
                  <td>{{ item.direction || '出' }}</td>
                  <td>{{ item.counterpartyName || '--' }}</td>
                  <td>{{ item.summary || '--' }}</td>
                  <td class="ta-r expense">{{ formatMoney(item.amount) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <el-empty v-if="analysis.accounts.length === 0" description="暂无账户数据"></el-empty>
        </div>
      </div>
    </div>

    <el-empty
      v-else-if="!loading"
      description="请选择户名查看账户概览；往来对手默认统计全部客户"
    ></el-empty>

    <div class="card">
        <div class="card-head">
          <span class="card-title">往来对手</span>
          <span class="card-sub" v-if="counterparties">
            {{ holder ? '当前客户：' + holder : '全部客户（未选择户名）' }} · 对手
            {{ counterparties.counterpartyCount }} 个 · 既有转入又有转出 {{ counterparties.dualCount }} 个 ·
            空户名排除 {{ counterparties.excludedBlankCount }} 笔
          </span>
        </div>

        <div class="cp-stats" v-if="counterparties">
          <span class="cp-stat">转入 <b class="income">{{ formatMoney(counterparties.totalIncome) }}</b></span>
          <span class="cp-stat">转出 <b class="expense">{{ formatMoney(counterparties.totalExpense) }}</b></span>
          <span class="cp-stat" :class="{ 'cp-warn': selfSharePercent >= 10 }">
            自有账户互转 {{ counterparties.selfTransferCount }} 笔 /
            {{ formatMoney(counterparties.selfTransferAmount) }}
            <template v-if="selfSharePercent > 0">（占往来总额 {{ selfSharePercent }}%）</template>
          </span>
        </div>

        <el-table
          v-if="counterparties && counterparties.items.length"
          :data="counterparties.items"
          border
          size="small"
          row-key="name"
          max-height="620"
          class="table cp-table"
          v-loading="cpLoading"
          @expand-change="onCpExpand"
        >
          <el-table-column type="expand" width="34">
            <template #default="{ row }">
              <div class="cp-detail">
                <div class="cp-filters">
                  <el-select
                    v-model="cpDetail[row.name].holder"
                    placeholder="全部客户"
                    clearable
                    size="small"
                    class="cp-filter"
                    @change="reloadCpDetail(row)"
                  >
                    <el-option v-for="h in counterparties.holders" :key="h" :label="h" :value="h" />
                  </el-select>
                  <el-select
                    v-model="cpDetail[row.name].accountNo"
                    placeholder="全部账户"
                    clearable
                    size="small"
                    class="cp-filter cp-filter-wide"
                    @change="reloadCpDetail(row)"
                  >
                    <el-option
                      v-for="a in cpAccountOptions(row)"
                      :key="a.accountNo"
                      :label="`${a.bankName || '未知银行'} ${a.accountNo}`"
                      :value="a.accountNo"
                    >
                      <span>{{ a.bankName || '未知银行' }} {{ a.accountNo }}</span>
                      <span class="option-extra">{{ a.accountHolder }}</span>
                    </el-option>
                  </el-select>
                  <el-date-picker
                    v-model="cpDetail[row.name].range"
                    type="daterange"
                    size="small"
                    value-format="YYYY-MM-DD"
                    start-placeholder="开始日期"
                    end-placeholder="结束日期"
                    class="cp-filter cp-filter-date"
                    @change="reloadCpDetail(row)"
                  />
                  <span class="card-sub" v-if="cpDetail[row.name].summary">
                    共 {{ cpDetail[row.name].rows.length }} 笔 · 转入
                    {{ cpDetail[row.name].summary?.incomeCount }} 笔 / {{ formatMoney(cpDetail[row.name].summary?.incomeTotal) }}
                    · 转出 {{ cpDetail[row.name].summary?.expenseCount }} 笔 /
                    {{ formatMoney(cpDetail[row.name].summary?.expenseTotal) }}
                  </span>
                </div>

                <div v-if="cpDetail[row.name].loading" class="cp-empty">加载中…</div>
                <template v-else-if="cpDetail[row.name].rows.length">
                  <table class="interest-table cp-detail-table">
                    <colgroup>
                      <col style="width: 92px" />
                      <col style="width: 48px" />
                      <col style="width: 108px" />
                      <col />
                      <col style="width: 24%" />
                      <col style="width: 16%" />
                      <col style="width: 56px" />
                    </colgroup>
                    <thead>
                      <tr>
                        <th>日期</th>
                        <th>方向</th>
                        <th class="ta-r">金额</th>
                        <th>摘要</th>
                        <th>账户</th>
                        <th>客户</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="item in cpDetail[row.name].rows" :key="item.id">
                        <td>{{ item.transDate }}</td>
                        <td :class="item.direction === '入' ? 'income' : 'expense'">
                          {{ item.direction === '入' ? '转入' : '转出' }}
                        </td>
                        <td class="ta-r" :class="item.direction === '入' ? 'income' : 'expense'">
                          {{ item.amount >= 0 ? '+' : '' }}{{ formatMoney(item.amount) }}
                        </td>
                        <td>
                          {{ item.summary || '--' }}
                          <span v-if="item.selfTransfer" class="cp-tag">自有互转</span>
                        </td>
                        <td>{{ item.bankName || '未知银行' }} {{ item.accountNo }}</td>
                        <td>
                          {{ item.accountHolder }}
                          <span
                            v-if="tagText(customerTags.tagsFor(item.accountHolder))"
                            class="risk-chip"
                            :class="tagClass(customerTags.tagsFor(item.accountHolder))"
                          >{{ tagText(customerTags.tagsFor(item.accountHolder)) }}</span>
                        </td>
                        <td>
                          <el-button text type="primary" size="small" @click="goStatementById(item.statementId, row.displayName)">
                            原流水
                          </el-button>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </template>
                <div v-else class="cp-empty">无符合条件的往来明细</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="对手" min-width="185">
            <template #default="{ row }">
              <div class="cp-name">
                {{ row.displayName }}
                <RiskTags :tags="counterpartyTags.tagsFor(row.displayName)" />
                <span v-if="row.selfTransferCount > 0" class="cp-tag">自有互转</span>
              </div>
              <div class="sub" v-if="row.holderCount > 1">{{ row.holderCount }} 个客户</div>
              <div class="cp-quick-tags" v-action:risk:view>
                <el-popconfirm
                  :title="isTagged(row, 'FUNDER') ? '解除「民间资方」标记？' : '标记为民间资方？'"
                  confirm-button-text="确定"
                  cancel-button-text="取消"
                  width="210"
                  @confirm="toggleTag(row, 'FUNDER')"
                >
                  <template #reference>
                    <span
                      class="cp-quick"
                      :class="{'cp-quick--on-funder': isTagged(row, 'FUNDER'), 'cp-quick--busy': isTagBusy(row, 'FUNDER')}"
                    >资方</span>
                  </template>
                </el-popconfirm>
                <el-popconfirm
                  :title="isTagged(row, 'BLACKLIST_CP') ? '解除「黑名单对手」标记？' : '标记为黑名单对手？'"
                  confirm-button-text="确定"
                  cancel-button-text="取消"
                  width="210"
                  @confirm="toggleTag(row, 'BLACKLIST_CP')"
                >
                  <template #reference>
                    <span
                      class="cp-quick"
                      :class="{'cp-quick--on-black': isTagged(row, 'BLACKLIST_CP'), 'cp-quick--busy': isTagBusy(row, 'BLACKLIST_CP')}"
                    >黑名单</span>
                  </template>
                </el-popconfirm>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="转入" min-width="118" align="right">
            <template #default="{ row }">
              <div class="income">{{ formatMoney(row.incomeTotal) }}</div>
              <div class="sub">{{ row.incomeCount }} 笔</div>
            </template>
          </el-table-column>
          <el-table-column label="转出" min-width="118" align="right">
            <template #default="{ row }">
              <div class="expense">{{ formatMoney(row.expenseTotal) }}</div>
              <div class="sub">{{ row.expenseCount }} 笔</div>
            </template>
          </el-table-column>
          <el-table-column label="净额" min-width="108" align="right">
            <template #default="{ row }">
              <span :class="row.netAmount >= 0 ? 'income' : 'expense'">
                {{ formatMoney(row.netAmount) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="最近往来" width="100">
            <template #default="{ row }">{{ row.lastDate || '--' }}</template>
          </el-table-column>
          <el-table-column label="账户数" width="64" align="right">
            <template #default="{ row }">{{ row.accountCount }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!cpLoading" description="暂无同时存在转入与转出的对手"></el-empty>
      </div>

      <div class="card" v-loading="kwLoading">
        <div class="card-head">
          <span class="card-title">关键词分析</span>
          <span class="card-sub">
            {{ holder ? '当前客户：' + holder : '全部客户（未选择户名）' }}
            <template v-if="keywordAnalysis">· 匹配范围 {{ keywordAnalysis.totalScanned }} 笔（转入/转出）</template>
          </span>
          <el-button text type="primary" size="small" class="kw-config-btn" @click="openKeywordConfig">
            关键词配置
          </el-button>
        </div>

        <div v-if="keywordAnalysis && keywordAnalysis.groups.length" class="kw-groups">
          <div v-for="g in keywordAnalysis.groups" :key="g.groupId" class="kw-group">
            <div class="kw-group-head">
              <span class="kw-group-title">{{ g.name }}</span>
              <el-tag size="small" type="info">{{ directionLabel(g.direction) }}</el-tag>
              <el-tag size="small" type="info">字段：{{ fieldsLabel(g.fields) }}</el-tag>
              <span class="kw-summary">
                命中 <b>{{ g.hitCount }}</b> 笔 · 命中率 <b>{{ g.hitRate }}%</b> ·
                合计 <b>{{ formatMoney(g.hitAmount) }}</b>
              </span>
            </div>

            <template v-if="g.direction === 'IN'">
              <el-table :data="g.months" border size="small" class="table kw-month-table">
                <el-table-column prop="month" label="月份" width="96"></el-table-column>
                <el-table-column label="入账合计" min-width="120" align="right">
                  <template #default="{ row }">
                    <span class="income">{{ formatMoney(row.incomeAmount) }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="incomeCount" label="笔数" width="64" align="right"></el-table-column>
                <el-table-column v-for="kw in g.keywordList" :key="kw" :label="kw" min-width="118" align="right">
                  <template #default="{ row }">
                    <div>{{ formatMoney(keywordMonthAmount(row, kw)) }}</div>
                    <div class="sub">{{ keywordMonthCount(row, kw) }} 笔</div>
                  </template>
                </el-table-column>
              </el-table>
              <div v-if="!g.months.length" class="kw-empty">该范围内无入账命中</div>
            </template>

            <template v-else>
              <table class="interest-table kw-table">
                <colgroup>
                  <col style="width: 92px" />
                  <col style="width: 20%" />
                  <col style="width: 44px" />
                  <col style="width: 104px" />
                  <col />
                  <col style="width: 19%" />
                  <col style="width: 12%" />
                  <col style="width: 56px" />
                </colgroup>
                <thead>
                  <tr>
                    <th>日期</th>
                    <th>对手</th>
                    <th>方向</th>
                    <th class="ta-r">金额</th>
                    <th>摘要 / 备注</th>
                    <th>账户</th>
                    <th>客户</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="row in keywordPageRows(g)" :key="row.id">
                    <td>{{ row.transDate }}</td>
                    <td>
                      {{ row.counterpartyName || '--' }}
                      <RiskTags :tags="counterpartyTags.tagsFor(row.counterpartyName)" />
                    </td>
                    <td :class="row.direction === '入' ? 'income' : 'expense'">
                      {{ row.direction === '入' ? '转入' : '转出' }}
                    </td>
                    <td class="ta-r" :class="row.direction === '入' ? 'income' : 'expense'">
                      {{ row.amount >= 0 ? '+' : '' }}{{ formatMoney(row.amount) }}
                    </td>
                    <td>
                      <div>{{ row.summary || '--' }}</div>
                      <div v-if="row.remark" class="sub">备注：{{ row.remark }}</div>
                    </td>
                    <td>{{ row.bankName || '未知银行' }} {{ row.accountNo }}</td>
                    <td>{{ row.accountHolder }}</td>
                    <td>
                      <el-button
                        text
                        type="primary"
                        size="small"
                        @click="goStatementById(row.statementId, row.counterpartyName || '')"
                      >
                        原流水
                      </el-button>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-if="!g.details.length" class="kw-empty">该范围内无命中</div>
              <div v-if="g.details.length > KW_PAGE_SIZE" class="kw-pager">
                <el-pagination
                  small
                  layout="prev, pager, next"
                  :page-size="KW_PAGE_SIZE"
                  :total="g.details.length"
                  v-model:current-page="kwPage[g.groupId]"
                />
                <span class="card-sub">共 {{ g.details.length }} 笔，按对手名称 + 日期排序</span>
              </div>
            </template>
          </div>
        </div>
        <el-empty
          v-else-if="!kwLoading"
          description="暂无启用的关键词分组，可点击右上角「关键词配置」新增"
        ></el-empty>
      </div>

      <el-drawer v-model="kwConfigVisible" title="关键词配置" size="640px">
        <div class="kw-cfg-bar">
          <el-button type="primary" size="small" @click="openGroupForm(null)">新增分组</el-button>
          <span class="card-sub">关键词用逗号分隔；匹配字段可多选；停用后不参与统计</span>
        </div>
        <el-table :data="keywordGroups" border size="small" class="table">
          <el-table-column prop="name" label="分组" width="120" show-overflow-tooltip></el-table-column>
          <el-table-column label="关键词" min-width="150">
            <template #default="{ row }">
              <el-tag v-for="kw in splitKeywords(row.keywords)" :key="kw" size="small" class="kw-kw-tag">
                {{ kw }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="字段" width="76">
            <template #default="{ row }">{{ fieldsLabel(row.fields) }}</template>
          </el-table-column>
          <el-table-column label="方向" width="76">
            <template #default="{ row }">{{ directionLabel(row.direction) }}</template>
          </el-table-column>
          <el-table-column label="启用" width="60" align="center">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                size="small"
                @change="toggleKeywordGroup(row, $event)"
              />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="104" align="center">
            <template #default="{ row }">
              <el-button text type="primary" size="small" @click="openGroupForm(row)">编辑</el-button>
              <el-button
                text
                type="danger"
                size="small"
                :disabled="row.builtin"
                @click="removeKeywordGroup(row)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-drawer>

      <el-dialog
        v-model="kwFormVisible"
        :title="kwForm.id ? '编辑关键词分组' : '新增关键词分组'"
        width="480px"
      >
        <el-form label-width="90px">
          <el-form-item label="分组名称">
            <el-input v-model="kwForm.name" placeholder="如：借款/还款" />
          </el-form-item>
          <el-form-item label="关键词">
            <el-input
              v-model="kwForm.keywords"
              type="textarea"
              :rows="2"
              placeholder="多个关键词用逗号分隔，如：借款,还款"
            />
          </el-form-item>
          <el-form-item label="匹配字段">
            <el-checkbox-group v-model="kwForm.fieldList">
              <el-checkbox label="remark">备注</el-checkbox>
              <el-checkbox label="summary">摘要</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-form-item label="方向范围">
            <el-select v-model="kwForm.direction" style="width: 100%">
              <el-option label="全部" value="ALL" />
              <el-option label="仅入账" value="IN" />
              <el-option label="仅出账" value="OUT" />
            </el-select>
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="kwForm.enabled" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="kwForm.remark" placeholder="选填" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="kwFormVisible = false">取消</el-button>
          <el-button type="primary" @click="saveKeywordGroup">保存</el-button>
        </template>
      </el-dialog>

      <div v-if="analysis" class="analysis-footer">
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
      </div>
  </div>
</template>

<script setup lang="ts">
import {computed, onMounted, onUnmounted, reactive, ref} from 'vue';
import {useRouter} from 'vue-router';
import {ElMessage, ElMessageBox} from 'element-plus';
import {Refresh, Search} from '@element-plus/icons-vue';
import {
  createKeywordGroup,
  deleteKeywordGroup,
  getCounterparties,
  getCounterpartyDetail,
  getHolderAnalysis,
  getKeywordAnalysis,
  getRiskAlerts,
  listAccountHolders,
  listKeywordGroups,
  setKeywordGroupEnabled,
  updateKeywordGroup
} from '../api/bankStatement';
import {useIsMobile} from '../composables/useIsMobile';
import {useRiskTags} from '../composables/useRiskTags';
import type {RiskTagItem} from '../composables/useRiskTags';
import type {RiskAlertSummary} from '../api/bankStatement';
import {linkRiskTag, unlinkRiskTag} from '../api/risk';
import RiskTags from '../components/RiskTags.vue';
import RiskAlertPanel from '../components/RiskAlertPanel.vue';

interface AccountHolderOption {
  accountHolder: string;
  statementCount: number;
  accountCount: number;
}

interface MaxBalanceTxn {
  transactionId: number;
  transDate: string;
  amount: number;
  direction: string;
  counterpartyName: string;
  summary: string;
  remark: string;
}

interface InterestItem {
  transactionId: number;
  transDate: string;
  amount: number;
  direction: string;
  counterpartyName: string;
  summary: string;
}

interface AccountBalanceOverview {
  balanceAvailable: boolean;
  rangeBasis: string;
  statStart: string;
  statEnd: string;
  statDays: number;
  openingBalance: number;
  closingBalance: number;
  avgDailyBalance: number;
  maxBalance: number;
  maxBalanceDate: string;
  maxBalanceSource: string;
  maxBalanceTxn: MaxBalanceTxn | null;
  note: string;
  interestCount: number;
  interestTotal: number;
  interests: InterestItem[];
  interestExpenseCount: number;
  interestExpenseTotal: number;
  interestExpenses: InterestItem[];
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
  overview: AccountBalanceOverview | null;
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

interface CounterpartyRow {
  name: string;
  displayName: string;
  incomeCount: number;
  incomeTotal: number;
  expenseCount: number;
  expenseTotal: number;
  netAmount: number;
  totalFlow: number;
  lastDate: string;
  accountCount: number;
  holderCount: number;
  transactionCount: number;
  selfTransferCount: number;
  selfTransferAmount: number;
}

interface CounterpartyAccountOption {
  accountNo: string;
  bankName: string;
  accountHolder: string;
}

interface CounterpartyAnalysis {
  counterpartyCount: number;
  dualCount: number;
  transactionCount: number;
  excludedBlankCount: number;
  totalIncome: number;
  totalExpense: number;
  selfTransferCount: number;
  selfTransferAmount: number;
  holders: string[];
  accounts: CounterpartyAccountOption[];
  items: CounterpartyRow[];
}

interface CounterpartyTxn {
  id: number;
  statementId: number;
  transDate: string;
  direction: string;
  amount: number;
  summary: string;
  bankName: string;
  accountNo: string;
  accountHolder: string;
  selfTransfer: boolean;
}

interface CounterpartyDetailSummary {
  incomeCount: number;
  incomeTotal: number;
  expenseCount: number;
  expenseTotal: number;
  netAmount: number;
}

interface CpDetailState {
  holder: string;
  accountNo: string;
  range: string[];
  loading: boolean;
  rows: CounterpartyTxn[];
  summary: CounterpartyDetailSummary | null;
}

interface KeywordStat {
  keyword: string;
  count: number;
  amount: number;
}

interface KeywordTxnRow {
  id: number;
  statementId: number;
  transDate: string;
  direction: string;
  amount: number;
  counterpartyName: string;
  summary: string;
  remark: string;
  bankName: string;
  accountNo: string;
  accountHolder: string;
  matchedKeywords: string[];
}

interface KeywordMonthRow {
  month: string;
  incomeAmount: number;
  incomeCount: number;
  keywordStats: KeywordStat[];
}

interface KeywordGroupResult {
  groupId: number;
  name: string;
  keywords: string;
  fields: string;
  keywordList: string[];
  direction: string;
  hitCount: number;
  hitRate: number;
  hitAmount: number;
  keywordStats: KeywordStat[];
  details: KeywordTxnRow[];
  months: KeywordMonthRow[];
}

interface KeywordAnalysisResult {
  scopeHolder: string;
  totalScanned: number;
  groups: KeywordGroupResult[];
}

interface KeywordGroupConfig {
  id: number;
  name: string;
  keywords: string;
  fields: string;
  direction: string;
  enabled: boolean;
  builtin: boolean;
  remark: string;
}

const holders = ref<AccountHolderOption[]>([]);
const holder = ref('');
const customerTags = useRiskTags('CUSTOMER');
const counterpartyTags = useRiskTags('COUNTERPARTY');
// 风险警示：当前户名（未选户名时为全部客户）范围内的命中汇总
const riskAlerts = ref<RiskAlertSummary | null>(null);
// 账户概览/逐笔明细区域用单文本标签，规避该页 Element Plus 展开表格的补丁错位问题
const tagText = (tags?: RiskTagItem[]) =>
  (tags || []).map((tag) => tag.name + (tag.source === 'SYSTEM' ? '(系统)' : '')).join(' / ');
const tagClass = (tags?: RiskTagItem[]) => 'risk-chip--' + (tags?.[0]?.color || 'info');
const analysis = ref<HolderAnalysis | null>(null);
const loading = ref(false);
const isMobile = useIsMobile();
const router = useRouter();
// 账户概览表格所需最小宽度；容器不足时改用卡片布局，避免出现横向滚动条
const OVERVIEW_TABLE_MIN_WIDTH = 1040;
const overviewCard = ref<HTMLElement | null>(null);
const overviewTableAllowed = ref(false);
let overviewObserver: ResizeObserver | null = null;

onMounted(() => {
  const el = overviewCard.value;
  if (!el) return;
  const update = () => {
    overviewTableAllowed.value = el.clientWidth >= OVERVIEW_TABLE_MIN_WIDTH;
  };
  update();
  if (typeof ResizeObserver !== 'undefined') {
    overviewObserver = new ResizeObserver(update);
    overviewObserver.observe(el as any);
  }
});

onUnmounted(() => {
  overviewObserver?.disconnect();
  overviewObserver = null;
});

const sortedAccounts = computed(() => {
  const list = [...(analysis.value?.accounts || [])];
  // 固定按日均余额降序；无余额数据的账户排最后
  return list.sort((a, b) => {
    const av = a.overview?.balanceAvailable ? Number(a.overview.avgDailyBalance) : null;
    const bv = b.overview?.balanceAvailable ? Number(b.overview.avgDailyBalance) : null;
    if (av === null && bv === null) return 0;
    if (av === null) return 1;
    if (bv === null) return -1;
    return bv - av;
  });
});

const formatMoney = (value: any) =>
  Number(value || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2});

const moneyOrDash = (value: any) =>
  value === null || value === undefined ? '—' : formatMoney(value);

// 卡片模式下展开逐笔结息明细的账户
const expandedInterest = reactive(new Set<number>());
const toggleInterest = (id: number) => {
  if (expandedInterest.has(id)) {
    expandedInterest.delete(id);
  } else {
    expandedInterest.add(id);
  }
};

const goStatement = (row: AccountSummary) => {
  router.push(`/bank-statements/${row.id}/transactions`);
};

// ========== 往来对手 ==========
const counterparties = ref<CounterpartyAnalysis | null>(null);
const cpLoading = ref(false);
const cpDetail = reactive<Record<string, CpDetailState>>({});

const selfSharePercent = computed(() => {
  const data = counterparties.value;
  if (!data) return 0;
  const total = Number(data.totalIncome || 0) + Number(data.totalExpense || 0);
  if (!total) return 0;
  return Math.round((Number(data.selfTransferAmount || 0) / total) * 1000) / 10;
});

const emptyCpState = (): CpDetailState => ({
  holder: '',
  accountNo: '',
  range: [],
  loading: false,
  rows: [],
  summary: null,
});

const loadCounterparties = () => {
  const scopeHolder = holder.value || undefined;
  cpLoading.value = true;
  getCounterparties(scopeHolder)
    .then((res) => {
      counterparties.value = res.data;
      // 范围变化后重置明细筛选项与已加载的逐笔数据
      for (const key of Object.keys(cpDetail)) {
        delete cpDetail[key];
      }
      for (const row of res.data?.items || []) {
        if (!cpDetail[row.name]) {
          cpDetail[row.name] = emptyCpState();
        }
      }
      counterpartyTags.load((res.data?.items || []).map((row: CounterpartyRow) => row.displayName));
    })
    .finally(() => {
      cpLoading.value = false;
    });
};

const cpAccountOptions = (row: CounterpartyRow) => {
  const accounts = counterparties.value?.accounts || [];
  const state = cpDetail[row.name];
  if (!state || !state.holder) return accounts;
  return accounts.filter((a) => a.accountHolder === state.holder);
};

const loadCpDetail = (row: CounterpartyRow) => {
  const state = cpDetail[row.name];
  if (!state) return;
  state.loading = true;
  getCounterpartyDetail({
    name: row.name,
    accountHolder: holder.value || state.holder || undefined,
    accountNo: state.accountNo || undefined,
    from: state.range?.[0] || undefined,
    to: state.range?.[1] || undefined,
  })
    .then((res) => {
      state.rows = res.data?.items || [];
      state.summary = res.data || null;
      // 往来对手逐笔明细里的客户名称标签
      customerTags.load(state.rows.map((item: any) => item.accountHolder));
    })
    .finally(() => {
      state.loading = false;
    });
};

const reloadCpDetail = (row: CounterpartyRow) => {
  const state = cpDetail[row.name];
  if (!state) return;
  state.rows = [];
  state.summary = null;
  loadCpDetail(row);
};

const onCpExpand = (row: CounterpartyRow, expandedRows: CounterpartyRow[]) => {
  if (expandedRows.some((item) => item.name === row.name)) {
    loadCpDetail(row);
  }
};

const goStatementById = (statementId: number, counterpartyName: string) => {
  router.push({path: `/bank-statements/${statementId}/transactions`, query: {keyword: counterpartyName}});
};

// ========== 关键词分析 ==========
const KW_PAGE_SIZE = 10;
const keywordAnalysis = ref<KeywordAnalysisResult | null>(null);
const keywordGroups = ref<KeywordGroupConfig[]>([]);
const kwLoading = ref(false);
const kwPage = reactive<Partial<Record<number, number>>>({});
const kwConfigVisible = ref(false);
const kwFormVisible = ref(false);
const kwForm = reactive({
  id: null as number | null,
  name: '',
  keywords: '',
  fieldList: ['remark', 'summary'] as string[],
  direction: 'ALL',
  enabled: true,
  remark: ''
});

const splitKeywords = (value: string) => (value || '').split(/[,，;；\s]+/).filter(Boolean);
const directionLabel = (direction: string) =>
  direction === 'IN' ? '仅入账' : direction === 'OUT' ? '仅出账' : '全部';
const fieldsLabel = (fields: string) =>
  splitKeywords(fields)
    .map((field) => (field === 'remark' ? '备注' : field === 'summary' ? '摘要' : field))
    .join('+') || '--';

const loadKeywordAnalysis = () => {
  kwLoading.value = true;
  getKeywordAnalysis(holder.value || undefined)
    .then((res) => {
      keywordAnalysis.value = res.data;
      const groups: KeywordGroupResult[] = res.data?.groups || [];
      counterpartyTags.load(groups.flatMap((group) => group.details.map((row) => row.counterpartyName)));
      for (const key of Object.keys(kwPage)) {
        if (!groups.some((group) => String(group.groupId) === key)) {
          delete kwPage[Number(key)];
        }
      }
    })
    .finally(() => {
      kwLoading.value = false;
    });
};

const keywordPageRows = (group: KeywordGroupResult) => {
  const page = kwPage[group.groupId] || 1;
  const start = (page - 1) * KW_PAGE_SIZE;
  return group.details.slice(start, start + KW_PAGE_SIZE);
};

const keywordMonthAmount = (row: KeywordMonthRow, keyword: string) =>
  row.keywordStats.find((stat) => stat.keyword === keyword)?.amount ?? 0;

const keywordMonthCount = (row: KeywordMonthRow, keyword: string) =>
  row.keywordStats.find((stat) => stat.keyword === keyword)?.count ?? 0;

const loadKeywordGroups = () => {
  listKeywordGroups().then((res) => {
    keywordGroups.value = res.data || [];
  });
};

const openKeywordConfig = () => {
  kwConfigVisible.value = true;
  loadKeywordGroups();
};

const openGroupForm = (row: KeywordGroupConfig | null) => {
  kwForm.id = row?.id ?? null;
  kwForm.name = row?.name ?? '';
  kwForm.keywords = row?.keywords ?? '';
  kwForm.fieldList = splitKeywords(row?.fields ?? 'remark,summary');
  kwForm.direction = row?.direction ?? 'ALL';
  kwForm.enabled = row?.enabled ?? true;
  kwForm.remark = row?.remark ?? '';
  kwFormVisible.value = true;
};

const saveKeywordGroup = () => {
  if (!kwForm.name.trim()) {
    ElMessage.warning('请填写分组名称');
    return;
  }
  if (!kwForm.keywords.trim()) {
    ElMessage.warning('请填写关键词');
    return;
  }
  if (!kwForm.fieldList.length) {
    ElMessage.warning('请至少选择一个匹配字段');
    return;
  }
  const payload = {
    name: kwForm.name.trim(),
    keywords: kwForm.keywords.trim(),
    fields: kwForm.fieldList.join(','),
    direction: kwForm.direction,
    enabled: kwForm.enabled,
    remark: kwForm.remark
  };
  const request = kwForm.id ? updateKeywordGroup(kwForm.id, payload) : createKeywordGroup(payload);
  request.then(() => {
    ElMessage.success('保存成功');
    kwFormVisible.value = false;
    loadKeywordGroups();
    loadKeywordAnalysis();
  });
};

const toggleKeywordGroup = (row: KeywordGroupConfig, value: boolean | string | number) => {
  setKeywordGroupEnabled(row.id, Boolean(value)).then(() => {
    ElMessage.success(Boolean(value) ? '已启用' : '已停用');
    loadKeywordGroups();
    loadKeywordAnalysis();
  });
};

const removeKeywordGroup = (row: KeywordGroupConfig) => {
  ElMessageBox.confirm(`确认删除分组「${row.name}」？`, '提示', {type: 'warning'})
    .then(() => deleteKeywordGroup(row.id))
    .then(() => {
      ElMessage.success('已删除');
      loadKeywordGroups();
      loadKeywordAnalysis();
    })
    .catch(() => {});
};

const loadHolders = () => {
  listAccountHolders().then((res) => {
    holders.value = res.data || [];
    customerTags.load(holders.value.map((item) => item.accountHolder));
    if (!holder.value && holders.value.length > 0) {
      holder.value = holders.value[0].accountHolder;
    }
    if (holder.value) {
      loadAnalysis();
    } else {
      analysis.value = null;
    }
    loadCounterparties();
    loadKeywordAnalysis();
    loadRiskAlerts();
  });
};

/** 风险警示汇总：选中户名时只看该客户，清空户名时统计全部客户 */
const loadRiskAlerts = () => {
  riskAlerts.value = null;
  getRiskAlerts({
    scope: holder.value ? 'HOLDER' : 'ALL',
    accountHolder: holder.value || undefined
  })
    .then((res: any) => {
      riskAlerts.value = res.data?.summary || null;
    })
    .catch(() => {
      riskAlerts.value = null;
    });
};

// ========== 往来对手排行快捷打标 ==========
const TAG_LABELS: Record<string, string> = {FUNDER: '资方', BLACKLIST_CP: '黑名单'};
// 按「对手名|标签」记录请求中状态，避免重复点击
const tagBusy = reactive<Record<string, boolean>>({});

const isTagged = (row: CounterpartyRow, code: string) =>
  counterpartyTags.tagsFor(row.displayName).some((tag) => tag.code === code);

const isTagBusy = (row: CounterpartyRow, code: string) =>
  !!tagBusy[row.name + '|' + code];

/** 快捷打标/解除：写入后刷新本行标签，并立即重算风险警示汇总 */
const toggleTag = (row: CounterpartyRow, code: string) => {
  const key = row.name + '|' + code;
  if (tagBusy[key]) {
    return;
  }
  tagBusy[key] = true;
  const tagged = isTagged(row, code);
  const label = TAG_LABELS[code];
  const request = tagged
    ? unlinkRiskTag('COUNTERPARTY', row.displayName, code)
    : linkRiskTag('COUNTERPARTY', row.displayName, code);
  request
    .then(() => {
      ElMessage.success(tagged ? `已解除${label}标记` : `已标记为${label}`);
      return counterpartyTags.refresh([row.displayName]);
    })
    .then(() => {
      loadRiskAlerts();
    })
    .catch(() => {
      // 错误提示已由请求拦截器统一弹出
    })
    .finally(() => {
      delete tagBusy[key];
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
      // 账户概览中出现的对手名称（最高余额对应、结息明细）批量取标签
      const names: Array<string | null | undefined> = [];
      for (const acc of (res.data as any)?.accounts || []) {
        const overview = acc.overview;
        if (!overview) continue;
        names.push(overview.maxBalanceTxn?.counterpartyName);
        for (const item of overview.interests || []) names.push(item.counterpartyName);
        for (const item of overview.interestExpenses || []) names.push(item.counterpartyName);
      }
      counterpartyTags.load(names);
    })
    .finally(() => {
      loading.value = false;
    });
};

const refreshAll = () => {
  loadHolders();
};

// 户名筛选同时作用于账户概览、往来对手与关键词分析；清空时回到全部客户范围
const onHolderChange = () => {
  loadAnalysis();
  loadCounterparties();
  loadKeywordAnalysis();
  loadRiskAlerts();
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
  gap: 8px;
}

.info-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fafbfc;
  color: #303133;
}

.ic-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.ic-title-wrap {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.ic-title {
  font-size: 14px;
  font-weight: 600;
}

.ic-sub {
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}

.ic-action {
  padding: 0;
  height: auto;
  flex: 0 0 auto;
}

.acct-line {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

.acct-name {
  font-weight: 600;
  word-break: break-all;
}

.overview-table :deep(.el-table__cell) {
  padding: 4px 0;
}

.overview-table :deep(.el-table__row) {
  line-height: 1.4;
}

.kv-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 14px;
}

.kv {
  display: flex;
  gap: 6px;
  min-width: 0;
  font-size: 12px;
  line-height: 1.6;
}

.kv .k {
  flex: 0 0 auto;
  color: #909399;
  white-space: nowrap;
}

.kv .v {
  min-width: 0;
  color: #303133;
  word-break: break-all;
}

.kv-strong .v {
  font-size: 14px;
  font-weight: 600;
}

.ic-interest {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.8;
}

.ic-interest .k {
  color: #909399;
}

.ic-interest .v {
  color: #303133;
}

.ic-interest .el-button {
  padding: 0;
  height: auto;
}

@media (min-width: 700px) {
  .kv-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (min-width: 1000px) {
  .kv-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
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

.sub {
  color: #909399;
  font-size: 12px;
}

.muted {
  color: #c0c4cc;
  font-size: 12px;
}

.overview-expand {
  display: flex;
  gap: 24px;
  padding: 8px 12px;
  flex-wrap: wrap;
}

.expand-block {
  flex: 1 1 320px;
  min-width: 0;
  overflow: hidden;
}

.expand-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.expand-text {
  font-size: 12px;
  color: #606266;
  line-height: 1.8;
}

.interest-table {
  width: 100%;
  max-width: 100%;
  table-layout: fixed;
  border-collapse: collapse;
  font-size: 12px;
  box-sizing: border-box;
}

.interest-table th,
.interest-table td {
  padding: 3px 6px;
  border-bottom: 1px dashed #ebeef5;
  white-space: normal;
  word-break: break-all;
  vertical-align: top;
  box-sizing: border-box;
}

.interest-table th {
  text-align: left;
  color: #909399;
  font-weight: 600;
  border-bottom: 1px solid #ebeef5;
}

.interest-table th.ta-r,
.interest-table td.ta-r {
  text-align: right;
  word-break: keep-all;
}

.interest-table {
  margin-top: 6px;
}

/* ========== 往来对手 ========== */
.cp-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  margin-bottom: 10px;
  font-size: 12px;
  color: #606266;
}

.cp-stat b {
  font-weight: 600;
}

.cp-stat.cp-warn {
  color: #d4380d;
  background: #fff2e8;
  border: 1px solid #ffbb96;
  border-radius: 4px;
  padding: 1px 8px;
}

.cp-table {
  font-size: 13px;
}

.cp-table :deep(.el-table__cell) {
  padding: 3px 0;
}

.cp-table :deep(.el-table__row) {
  line-height: 1.35;
}

.cp-name {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  word-break: break-all;
}

/* 排行行内快捷打标 */
.cp-quick-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.cp-quick {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 6px;
  border: 1px dashed #dcdfe6;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
  color: #909399;
  cursor: pointer;
  user-select: none;
}

.cp-quick:hover {
  color: #409eff;
  border-color: #409eff;
}

.cp-quick--on-funder {
  color: #e6a23c;
  background: #fdf6ec;
  border: 1px solid #faecd8;
}

.cp-quick--on-black {
  color: #f56c6c;
  background: #fef0f0;
  border: 1px solid #fde2e2;
}

.cp-quick--busy {
  opacity: 0.55;
  pointer-events: none;
}

/* 户名/对手标签统一由 RiskTags 组件渲染 */

/* 概览/逐笔明细里的单文本标签（该区域用文本节点避免组件补丁问题） */
.risk-chip {
  display: inline-block;
  padding: 0 5px;
  margin-left: 4px;
  border: 1px solid transparent;
  border-radius: 3px;
  font-size: 11px;
  line-height: 17px;
  white-space: nowrap;
  vertical-align: middle;
}

.risk-chip--danger {
  color: #f56c6c;
  background: #fef0f0;
  border-color: #fde2e2;
}

.risk-chip--warning {
  color: #e6a23c;
  background: #fdf6ec;
  border-color: #faecd8;
}

.risk-chip--success {
  color: #67c23a;
  background: #f0f9eb;
  border-color: #e1f3d8;
}

.risk-chip--primary {
  color: #409eff;
  background: #ecf5ff;
  border-color: #d9ecff;
}

.risk-chip--info {
  color: #909399;
  background: #f4f4f5;
  border-color: #e9e9eb;
}

.cp-tag {
  display: inline-block;
  font-size: 11px;
  line-height: 16px;
  color: #d4380d;
  background: #fff2e8;
  border: 1px solid #ffbb96;
  border-radius: 3px;
  padding: 0 4px;
  white-space: nowrap;
}

.cp-detail {
  padding: 2px 12px 10px;
}

.cp-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin: 4px 0 8px;
}

.cp-filter {
  width: 150px;
}

.cp-filter-wide {
  width: 260px;
}

.cp-filter-date {
  width: 250px;
}

.cp-empty {
  font-size: 12px;
  color: #909399;
  padding: 6px 2px;
}

.cp-detail-table td,
.cp-detail-table th {
  padding: 3px 6px;
}

/* ========== 关键词分析 ========== */
.kw-config-btn {
  margin-left: auto;
}

.kw-groups {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.kw-group {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 8px 10px;
}

.kw-group-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px 8px;
  margin-bottom: 8px;
}

.kw-group-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}

.kw-summary {
  margin-left: auto;
  font-size: 12px;
  color: #606266;
}

.kw-summary b {
  font-weight: 600;
}

.kw-month-table {
  font-size: 13px;
}

.kw-table {
  margin-top: 0;
  font-size: 13px;
}

.kw-table td,
.kw-table th {
  padding: 3px 6px;
}

.kw-empty {
  font-size: 12px;
  color: #909399;
  padding: 6px 2px;
}

.kw-pager {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.kw-cfg-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 10px;
}

.kw-kw-tag {
  margin: 0 4px 2px 0;
}

@media (max-width: 768px) {
  .cp-filter,
  .cp-filter-wide,
  .cp-filter-date {
    width: 100%;
  }
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
