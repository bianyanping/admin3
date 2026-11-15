<template>
  <div class="page">
    <div class="handle-box">
      <el-select v-model="query.bankCode" placeholder="银行" class="handle-select" clearable>
        <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value"></el-option>
      </el-select>
      <el-input v-model="query.accountHolder" placeholder="户名" class="handle-input" clearable></el-input>
      <el-input v-model="query.accountNo" placeholder="账号" class="handle-input" clearable></el-input>
      <el-date-picker
        v-model="query.dateRange"
        type="daterange"
        range-separator="至"
        start-placeholder="账期开始"
        end-placeholder="账期结束"
        value-format="YYYY-MM-DD"
        :style="isMobile ? { width: '100%', flex: 'none', boxSizing: 'border-box' } : { width: '260px', flex: 'none' }"
        clearable
      ></el-date-picker>
      <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      <el-button type="success" :icon="Upload" v-action:bankStatement:upload @click="uploadVisible = true">上传流水</el-button>
      <el-button type="primary" :icon="Plus" v-action:bankStatement:upload @click="openCreate">新增</el-button>
    </div>

    <div v-if="!isMobile" ref="tableWrap" class="table-wrap">
      <el-table :data="tableData" :height="tableHeight" border class="table" header-cell-class-name="table-header">
      <el-table-column prop="id" label="ID" width="60" align="center"></el-table-column>
      <el-table-column prop="bankName" label="银行" min-width="140"></el-table-column>
      <el-table-column prop="accountHolder" label="户名" min-width="120"></el-table-column>
      <el-table-column prop="accountNo" label="账号" min-width="180"></el-table-column>
      <el-table-column label="账期" width="220">
        <template #default="{ row }">
          {{ row.startDate }} ~ {{ row.endDate }}
        </template>
      </el-table-column>
      <el-table-column prop="currency" label="币种" width="80" align="center"></el-table-column>
      <el-table-column prop="transactionCount" label="笔数" width="80" align="center"></el-table-column>
      <el-table-column label="收入" width="140" align="right">
        <template #default="{ row }">
          <span class="income">{{ row.totalIncome }}</span>
          <span class="sub">({{ row.incomeCount }})</span>
        </template>
      </el-table-column>
      <el-table-column label="支出" width="140" align="right">
        <template #default="{ row }">
          <span class="expense">{{ row.totalExpense }}</span>
          <span class="sub">({{ row.expenseCount }})</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="导入时间" width="170"></el-table-column>
      <el-table-column label="数据归属" width="120" align="center">
        <template #default="{ row }">
          <el-tag :type="row.orgName ? 'warning' : 'info'" disable-transitions>{{ row.orgName || '根节点' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right" align="center">
        <template #default="{ row }">
          <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row)">
            <el-button text type="primary">
              操作<el-icon class="el-icon--right"><ArrowDown /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <span v-action:bankStatement:transaction:view>
                  <el-dropdown-item command="detail" :icon="View">明细</el-dropdown-item>
                </span>
                <span v-action:bankStatement:download>
                  <el-dropdown-item command="download" :icon="Download">下载原文件</el-dropdown-item>
                </span>
                <span v-action:bankStatement:share>
                  <el-dropdown-item command="share" :icon="Share">分享</el-dropdown-item>
                </span>
                <span v-action:bankStatement:upload>
                  <el-dropdown-item command="edit" :icon="Edit" divided>编辑</el-dropdown-item>
                </span>
                <span v-action:bankStatement:upload>
                  <el-dropdown-item command="delete" :icon="Delete">
                    <span style="color: #f56c6c">删除</span>
                  </el-dropdown-item>
                </span>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </el-table-column>
    </el-table>
    </div>

    <!-- 移动端：卡片式列表 -->
    <div v-else class="card-list">
      <div class="stmt-card" v-for="row in tableData" :key="row.id">
        <div class="card-head">
          <span class="card-title">{{ row.bankName }}</span>
          <el-tag :type="row.orgName ? 'warning' : 'info'" size="small" disable-transitions>{{ row.orgName || '根节点' }}</el-tag>
        </div>
        <div class="card-row"><span class="k">户名</span><span class="v">{{ row.accountHolder }}</span></div>
        <div class="card-row"><span class="k">账号</span><span class="v">{{ row.accountNo }}</span></div>
        <div class="card-row"><span class="k">账期</span><span class="v">{{ row.startDate }} ~ {{ row.endDate }}</span></div>
        <div class="card-row">
          <span class="k">收支</span>
          <span class="v">
            <span class="income">{{ row.totalIncome }}</span>
            <span class="expense" style="margin-left: 12px">{{ row.totalExpense }}</span>
          </span>
        </div>
        <div class="card-row">
          <span class="k">笔数</span>
          <span class="v">{{ row.transactionCount }} 笔（收 {{ row.incomeCount }} / 支 {{ row.expenseCount }}）</span>
        </div>
        <div class="card-actions">
          <el-button text type="primary" :icon="View" @click="goDetail(row)" v-action:bankStatement:transaction:view>明细</el-button>
          <el-button text :icon="Download" @click="handleDownload(row)" v-action:bankStatement:download>原文件</el-button>
          <el-button text type="warning" :icon="Share" @click="openShare(row)" v-action:bankStatement:share>分享</el-button>
          <el-button text type="primary" :icon="Edit" @click="openEdit(row)" v-action:bankStatement:upload>编辑</el-button>
          <el-button text type="danger" :icon="Delete" @click="handleDelete(row)" v-action:bankStatement:upload>删除</el-button>
        </div>
      </div>
      <el-empty v-if="tableData.length === 0" description="暂无流水数据"></el-empty>
    </div>

    <div class="pagination">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :small="isMobile"
        :current-page="query.pageIndex"
        :page-size="query.pageSize"
        :total="pageTotal"
        @current-change="handlePageChange"
      ></el-pagination>
    </div>

    <!-- 上传弹窗 -->
    <el-dialog title="上传银行流水 PDF" v-model="uploadVisible" width="min(520px, 92vw)">
      <el-form label-width="90px">
        <el-form-item label="银行">
          <el-select v-model="uploadForm.bankCode" placeholder="可自动识别；识别不到时会提示手动选择" clearable style="width: 100%">
            <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="PDF 文件">
          <el-upload
            ref="uploadRef"
            drag
            multiple
            :auto-upload="false"
            :limit="20"
            accept=".pdf"
            :disabled="uploading"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
            :on-exceed="handleExceed"
          >
            <el-icon class="el-icon--upload"><upload-filled /></el-icon>
            <div class="el-upload__text">将 PDF 拖到此处，或<em>点击选择</em></div>
            <template #tip>
              <div class="el-upload__tip">可一次选择多个 PDF（最多 20 个），系统自动判断原生 / 扫描件</div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <div v-if="uploading" class="upload-progress">
        正在解析第 {{ uploadIndex }} / {{ uploadTotal }} 个：{{ uploadCurrentName }}
      </div>
      <template #footer>
        <el-button :disabled="uploading" @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">
          {{ uploadFiles.length > 1 ? `开始解析入库（${uploadFiles.length} 个）` : '开始解析入库' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 新增 / 编辑流水单 -->
    <el-dialog :title="editingId ? `编辑流水单 #${editingId}` : '新增流水单'" v-model="formVisible" width="min(480px, 92vw)">
      <el-form label-width="90px">
        <el-form-item label="银行" required>
          <el-select v-model="form.bankCode" placeholder="请选择银行" style="width: 100%">
            <el-option v-for="b in bankOptions" :key="b.value" :label="b.label" :value="b.value"></el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="户名" required>
          <el-input v-model="form.accountHolder" placeholder="账户户名" clearable></el-input>
        </el-form-item>
        <el-form-item label="账号" required>
          <el-input v-model="form.accountNo" placeholder="账号 / 卡号" clearable></el-input>
        </el-form-item>
        <el-form-item label="账期" required>
          <el-date-picker
            v-model="form.dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            style="width: 100%"
          ></el-date-picker>
        </el-form-item>
        <el-form-item label="币种">
          <el-input v-model="form.currency" placeholder="人民币" clearable></el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveForm">保存</el-button>
      </template>
    </el-dialog>

    <!-- 分享弹窗 -->
    <el-dialog :title="`分享流水单 #${shareRow?.id} 给其他用户`" v-model="shareVisible" width="min(520px, 92vw)">
      <div class="share-line">
        <el-select v-model="shareUserId" placeholder="选择要分享给的用户" filterable style="flex: 1">
          <el-option v-for="u in shareUsers" :key="u.id" :label="`${u.username}（${u.orgFullName || '未分配组织'}）`" :value="u.id"></el-option>
        </el-select>
        <el-button type="primary" :disabled="!shareUserId" @click="doShare">分享</el-button>
      </div>
      <el-divider content-position="left">已分享给</el-divider>
      <div v-if="shareList.length === 0" class="share-empty">暂无分享记录</div>
      <div v-for="s in shareList" :key="s.granteeUserId" class="share-line share-item">
        <span>{{ s.granteeName }}</span>
        <el-button text type="danger" size="small" @click="doRevoke(s)">取消分享</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, reactive, ref} from 'vue';
import {useRouter} from 'vue-router';
import {ElMessage, ElMessageBox} from 'element-plus';
import {Download, Search, Share, Upload, UploadFilled, View, Plus, Edit, Delete, ArrowDown} from '@element-plus/icons-vue';
import {
  createStatement, deleteStatement, updateStatement,
  downloadSourceFile, importStatement, listShareUsers, listShares,
  listStatements, revokeShare, saveBlob, shareStatement
} from '../api/bankStatement';
import {useIsMobile} from '../composables/useIsMobile';
import {useFillHeight} from '../composables/useFillHeight';

const router = useRouter();
const isMobile = useIsMobile();
// 桌面端：表格高度跟随容器，数据多时表格内部滚动，页面不出现上下滚动条
const tableWrap = ref<HTMLElement | null>(null);
const {height: tableHeight} = useFillHeight(tableWrap);

const bankOptions = [
  {value: 'ICBC', label: '中国工商银行'},
  {value: 'CCB', label: '中国建设银行'},
  {value: 'PSBC', label: '中国邮政储蓄银行'},
  {value: 'GXRCB', label: '广西农商联合银行'},
  {value: 'ABCHX', label: '中国农业银行'},
  {value: 'GLGMCZYH', label: '桂林国民村镇银行'},
  {value: 'GLYH', label: '桂林银行'},
];

const query = reactive({
  bankCode: '',
  accountHolder: '',
  accountNo: '',
  dateRange: [] as string[],
  pageIndex: 1,
  pageSize: 20,
});
const tableData = ref<any[]>([]);
const pageTotal = ref(0);

const loadData = () => {
  listStatements({
    bankCode: query.bankCode || undefined,
    accountHolder: query.accountHolder || undefined,
    accountNo: query.accountNo || undefined,
    startDateFrom: query.dateRange?.[0] || undefined,
    startDateTo: query.dateRange?.[1] || undefined,
    page: query.pageIndex,
    size: query.pageSize,
  }).then((res: any) => {
    tableData.value = res.data.content || [];
    pageTotal.value = res.data.totalElements || 0;
  });
};

onMounted(loadData);

const handleSearch = () => {
  query.pageIndex = 1;
  loadData();
};
const handlePageChange = (val: number) => {
  query.pageIndex = val;
  loadData();
};

const goDetail = (row: any) => {
  router.push(`/bank-statements/${row.id}/transactions`);
};

const handleDownload = (row: any) => {
  if (!row.sourceFileKey) {
    ElMessage.warning('该流水单未保存原始文件，请重新上传后即可下载');
    return;
  }
  downloadSourceFile(row.id).then((res: any) => {
    saveBlob(res, row.sourceFileName || `${row.bankName}_${row.accountNo}.pdf`);
  }).catch(() => {
    // 错误提示已由请求拦截器统一弹出
  });
};

// ========== 上传 ==========
const uploadVisible = ref(false);
const uploading = ref(false);
const uploadForm = reactive({bankCode: ''});
const uploadRef = ref<any>(null);
const uploadFiles = ref<any[]>([]);
const uploadIndex = ref(0);
const uploadTotal = ref(0);
const uploadCurrentName = ref('');

const isPdfFile = (f: any) => (f.name || '').toLowerCase().endsWith('.pdf')
  || f.raw?.type === 'application/pdf';

const handleFileChange = (file: any, files: any[]) => {
  if (!isPdfFile(file)) {
    ElMessage.error(`仅支持 PDF 文件：${file.name}`);
    uploadRef.value?.handleRemove(file);
    return;
  }
  uploadFiles.value = files.filter((f: any) => f.raw);
};

const handleFileRemove = (file: any, files: any[]) => {
  uploadFiles.value = files.filter((f: any) => f.raw);
};

const handleExceed = () => {
  ElMessage.warning('一次最多上传 20 个文件');
};

/**
 * 多文件逐个上传：单个文件失败不影响其余文件，最后统一汇总结果。
 */
const submitUpload = async () => {
  const files = uploadFiles.value.filter(f => f.raw);
  if (!files.length) {
    ElMessage.warning('请选择文件');
    return;
  }

  uploading.value = true;
  uploadTotal.value = files.length;
  uploadIndex.value = 0;
  const okLines: string[] = [];
  const skipLines: string[] = [];
  const failLines: string[] = [];
  const warningLines: string[] = [];
  const failedUids = new Set<number>();

  for (let i = 0; i < files.length; i++) {
    const file = files[i];
    uploadIndex.value = i + 1;
    uploadCurrentName.value = file.name;
    const formData = new FormData();
    formData.append('file', file.raw as File);
    if (uploadForm.bankCode) formData.append('bankCode', uploadForm.bankCode);
    try {
      const res: any = await importStatement(formData, {silent: true});
      const r = res.data;
      const title = `${r.bankName || ''} ${r.accountHolder || ''}`.trim();
      if (r.duplicated) {
        skipLines.push(`${file.name}：该账期已存在，未重复入库`);
      } else if (r.merged) {
        skipLines.push(`${file.name}：账期重叠，已与同账号流水合并去重（${r.transactionCount} 笔）`);
      } else {
        okLines.push(`${file.name}：${title}，共 ${r.transactionCount} 笔`);
      }
      if (r.warnings && r.warnings.length) {
        warningLines.push(`${file.name}：<br>${r.warnings.map((w: string) => `&nbsp;&nbsp;· ${w}`).join('<br>')}`);
      }
    } catch (e: any) {
      const data = e?.response?.data;
      failLines.push(`${file.name}：${data?.message || '解析失败'}`);
      failedUids.add(file.uid);
    }
  }

  uploading.value = false;
  uploadIndex.value = 0;
  uploadCurrentName.value = '';

  const single = files.length === 1;
  const successCount = okLines.length + skipLines.length;
  if (single && failLines.length === 0 && warningLines.length === 0) {
    ElMessage.success(skipLines.length ? skipLines[0].replace(/^[^：]*：/, '') : `解析成功：${okLines[0].replace(/^[^：]*：/, '')}`);
  } else {
    const blocks: string[] = [];
    if (okLines.length) blocks.push(`<b>成功 ${okLines.length} 个</b><br>${okLines.join('<br>')}`);
    if (skipLines.length) blocks.push(`<b>跳过 ${skipLines.length} 个</b><br>${skipLines.join('<br>')}`);
    if (failLines.length) blocks.push(`<b style="color:#f56c6c">失败 ${failLines.length} 个</b><br>${failLines.join('<br>')}`);
    if (warningLines.length) blocks.push(`<b style="color:#e6a23c">校验提示</b><br>${warningLines.join('<br>')}`);
    ElMessageBox.alert(
      `<div style="max-height:360px;overflow:auto;line-height:1.9">${blocks.join('<br><br>')}</div>`,
      `上传结果（共 ${files.length} 个，成功 ${successCount} 个）`,
      {type: failLines.length ? 'error' : 'warning', dangerouslyUseHTMLString: true, confirmButtonText: '我知道了'}
    );
  }

  if (successCount > 0) {
    loadData();
  }
  if (failLines.length > 0) {
    // 弹窗保持打开，只留下失败的文件，方便重试
    files.filter((f: any) => !failedUids.has(f.uid))
      .forEach((f: any) => uploadRef.value?.handleRemove(f));
  } else {
    uploadVisible.value = false;
    uploadFiles.value = [];
    uploadRef.value?.clearFiles();
  }
};

// ========== 数据共享 ==========
const shareVisible = ref(false);
const shareRow = ref<any>(null);
const shareUserId = ref<number | null>(null);
const shareUsers = ref<any[]>([]);
const shareList = ref<any[]>([]);

const openShare = (row: any) => {
  shareRow.value = row;
  shareUserId.value = null;
  shareVisible.value = true;
  // 并行加载可选用户与已有分享记录
  listShareUsers().then((res: any) => {
    shareUsers.value = (res.data || []).filter((u: any) => u.username !== 'admin');
  });
  refreshShareList();
};
const refreshShareList = () => {
  listShares(shareRow.value.id).then((res: any) => {
    shareList.value = res.data || [];
  });
};
const doShare = () => {
  shareStatement(shareRow.value.id, shareUserId.value!).then(() => {
    ElMessage.success('分享成功，对方即可查看该流水单');
    shareUserId.value = null;
    refreshShareList();
  }).catch(() => {});
};
const doRevoke = (s: any) => {
  revokeShare(shareRow.value.id, s.granteeUserId).then(() => {
    ElMessage.success('已取消分享');
    refreshShareList();
  }).catch(() => {});
};

// ========== 手动新增 / 修改 / 删除 ==========
const handleCommand = (cmd: string, row: any) => {
  switch (cmd) {
    case 'detail': goDetail(row); break;
    case 'download': handleDownload(row); break;
    case 'share': openShare(row); break;
    case 'edit': openEdit(row); break;
    case 'delete': handleDelete(row); break;
  }
};

const formVisible = ref(false);
const saving = ref(false);
const editingId = ref<number | null>(null);
const form = reactive({
  bankCode: '',
  accountHolder: '',
  accountNo: '',
  dateRange: [] as string[],
  currency: '人民币',
});

const bankLabel = (code: string) => bankOptions.find(b => b.value === code)?.label || code;

const openCreate = () => {
  editingId.value = null;
  Object.assign(form, {bankCode: '', accountHolder: '', accountNo: '', dateRange: [], currency: '人民币'});
  formVisible.value = true;
};

const openEdit = (row: any) => {
  editingId.value = row.id;
  Object.assign(form, {
    bankCode: row.bankCode || '',
    accountHolder: row.accountHolder || '',
    accountNo: row.accountNo || '',
    dateRange: row.startDate && row.endDate ? [row.startDate, row.endDate] : [],
    currency: row.currency || '人民币',
  });
  formVisible.value = true;
};

const saveForm = () => {
  if (!form.bankCode) { ElMessage.warning('请选择银行'); return; }
  if (!form.accountHolder.trim()) { ElMessage.warning('请填写户名'); return; }
  if (!form.accountNo.trim()) { ElMessage.warning('请填写账号'); return; }
  if (!form.dateRange || form.dateRange.length !== 2) { ElMessage.warning('请选择账期'); return; }

  const payload = {
    bankCode: form.bankCode,
    bankName: bankLabel(form.bankCode),
    accountHolder: form.accountHolder.trim(),
    accountNo: form.accountNo.trim(),
    startDate: form.dateRange[0],
    endDate: form.dateRange[1],
    currency: form.currency || '人民币',
  };
  saving.value = true;
  const req = editingId.value ? updateStatement(editingId.value, payload) : createStatement(payload);
  req.then(() => {
    ElMessage.success(editingId.value ? '修改成功' : '新增成功');
    formVisible.value = false;
    loadData();
  }).finally(() => {
    saving.value = false;
  });
};

const handleDelete = (row: any) => {
  ElMessageBox.confirm(
    `确定删除流水单 #${row.id}（${row.accountHolder} ${row.accountNo}）？该流水单下的全部明细将一并删除。`,
    '删除确认',
    {type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消'}
  ).then(() => {
    deleteStatement(row.id).then(() => {
      ElMessage.success('已删除');
      loadData();
    });
  }).catch(() => {});
};
</script>

<style scoped>
/* 页面铺满内容区；高度固定为一屏，表格内部滚动 */
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
.handle-box { flex: none; margin-bottom: 20px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.handle-box > * { margin: 0; }
.handle-select { width: 160px; }
.handle-input { width: 200px; }
.table-wrap { flex: 1 1 auto; min-height: 0; }
.table { width: 100%; font-size: 14px; }
.pagination { flex: none; margin-top: 16px; }
.income { color: #67c23a; }
.expense { color: #f56c6c; }
.sub { color: #909399; font-size: 12px; margin-left: 4px; }
.share-line { display: flex; gap: 10px; align-items: center; }
.share-item { justify-content: space-between; padding: 6px 4px; border-bottom: 1px dashed #ebeef5; }
.share-empty { color: #909399; font-size: 13px; padding: 8px 4px; }
.upload-progress {
  margin-top: -4px;
  padding: 8px 10px;
  background: #f4f8ff;
  border-radius: 4px;
  color: #409eff;
  font-size: 13px;
  word-break: break-all;
}

@media (max-width: 768px) {
  .handle-select,
  .handle-input { width: 100%; }
  .page { padding: 10px 8px; height: auto; min-height: 100%; display: block; }
}

/* 移动端卡片式列表 */
.card-list { display: flex; flex-direction: column; gap: 10px; }
.stmt-card {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px 14px;
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 8px;
  margin-bottom: 8px;
  border-bottom: 1px solid #f0f0f0;
}
.card-title { font-size: 15px; font-weight: 600; color: #303133; }
.card-row { display: flex; font-size: 13px; line-height: 24px; }
.card-row .k { flex: 0 0 48px; color: #909399; }
.card-row .v { flex: 1; color: #303133; word-break: break-all; }
.card-actions {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0f0f0;
}
</style>
