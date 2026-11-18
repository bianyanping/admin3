<template>
  <div class="page">
    <div class="handle-box">
      <el-input v-model="query.keyword" placeholder="平台名称/关键词" class="handle-input" clearable
                @keyup.enter="handleSearch"></el-input>
      <el-select v-model="query.enabled" placeholder="状态" class="handle-select" clearable>
        <el-option label="启用" :value="true"></el-option>
        <el-option label="停用" :value="false"></el-option>
      </el-select>
      <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
      <el-button type="success" :icon="Plus" @click="openCreate">新增平台</el-button>
      <el-button type="warning" :icon="RefreshRight" :loading="refreshing" @click="handleRefresh">一键初始化</el-button>
      <el-button :icon="Connection" :loading="matching" @click="handleMatch">重新匹配流水</el-button>
    </div>

    <div class="page-tip">
      「其他平台」为系统内置兜底项：只有所有正式平台都未命中时才计入，关键词可修改、不可删除。
    </div>

    <el-table :data="tableData" border class="table" header-cell-class-name="table-header">
      <el-table-column prop="id" label="ID" width="60" align="center"></el-table-column>
      <el-table-column label="平台名称" width="180">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <el-tag v-if="row.builtin" size="small" type="warning" class="builtin-tag" disable-transitions>系统内置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="匹配关键词" min-width="260">
        <template #default="{ row }">
          <el-tag v-for="kw in splitKeywords(row.keywords)" :key="kw" class="keyword-tag" size="small"
                  disable-transitions>{{ kw }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            @change="(val: boolean) => handleToggle(row, val)"
          ></el-switch>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="180" show-overflow-tooltip></el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="170"></el-table-column>
      <el-table-column label="操作" width="120" align="center" fixed="right">
        <template #default="{ row }">
          <el-button text type="primary" :icon="Edit" size="small" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="!row.builtin" text type="danger" :icon="Delete" size="small"
                     @click="handleDelete(row)">删除</el-button>
          <el-button v-else text type="info" size="small" disabled>不可删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="tableData.length === 0" description="暂无平台，可点击「一键初始化」导入常见网贷平台"></el-empty>

    <div class="pagination">
      <el-pagination
        background
        layout="total, prev, pager, next"
        :current-page="query.page"
        :page-size="query.size"
        :total="total"
        @current-change="handlePageChange"
      ></el-pagination>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑网贷平台' : '新增网贷平台'" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="平台名称" required>
          <el-input v-model="form.name" placeholder="如：借呗、微粒贷" maxlength="128"
                    :disabled="editingBuiltin"></el-input>
          <div v-if="editingBuiltin" class="form-tip">系统内置兜底项名称固定，关键词可修改，平台不可删除。</div>
        </el-form-item>
        <el-form-item label="匹配关键词" required>
          <el-input
            v-model="form.keywords"
            type="textarea"
            :rows="3"
            placeholder="多个关键词用英文逗号分隔，如：借呗,蚂蚁借呗"
            maxlength="512"
          ></el-input>
          <div class="form-tip">交易对手户名或摘要命中任一关键词即判为网贷相关；多个关键词命中时取最长的一条。</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled"></el-switch>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" placeholder="可选" maxlength="255"></el-input>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {onMounted, ref} from 'vue';
import {ElMessage, ElMessageBox} from 'element-plus';
import {Connection, Delete, Edit, Plus, RefreshRight, Search} from '@element-plus/icons-vue';
import {
  createPlatform,
  deletePlatform,
  initPlatforms,
  listPlatforms,
  refreshMatches,
  setPlatformEnabled,
  updatePlatform,
} from '../api/lending';

interface LendingPlatform {
  id: number;
  name: string;
  keywords: string;
  enabled: boolean;
  builtin: boolean;
  remark: string;
  createTime: string;
  updateTime: string;
}

const tableData = ref<LendingPlatform[]>([]);
const total = ref(0);
const query = ref({
  keyword: '',
  enabled: undefined as boolean | undefined,
  page: 1,
  size: 20,
});

const dialogVisible = ref(false);
const editingId = ref<number | null>(null);
const editingBuiltin = ref(false);
const saving = ref(false);
const refreshing = ref(false);
const matching = ref(false);
const form = ref({
  name: '',
  keywords: '',
  enabled: true,
  remark: '',
});

const splitKeywords = (value: string) =>
  (value || '').split(/[,，;；、]/).map(item => item.trim()).filter(Boolean);

const loadData = () => {
  listPlatforms({
    keyword: query.value.keyword || undefined,
    enabled: query.value.enabled,
    page: query.value.page,
    size: query.value.size,
  }).then((res) => {
    tableData.value = res.data?.content || [];
    total.value = res.data?.totalElements || 0;
  });
};

const handleSearch = () => {
  query.value.page = 1;
  loadData();
};

const handlePageChange = (page: number) => {
  query.value.page = page;
  loadData();
};

const openCreate = () => {
  editingId.value = null;
  editingBuiltin.value = false;
  form.value = {name: '', keywords: '', enabled: true, remark: ''};
  dialogVisible.value = true;
};

const openEdit = (row: LendingPlatform) => {
  editingId.value = row.id;
  editingBuiltin.value = !!row.builtin;
  form.value = {
    name: row.name,
    keywords: row.keywords,
    enabled: row.enabled,
    remark: row.remark || '',
  };
  dialogVisible.value = true;
};

const handleSave = () => {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写平台名称');
    return;
  }
  if (!form.value.keywords.trim()) {
    ElMessage.warning('请填写匹配关键词');
    return;
  }
  saving.value = true;
  const request = editingId.value
    ? updatePlatform(editingId.value, form.value)
    : createPlatform(form.value);
  request.then(() => {
    ElMessage.success('保存成功，如需更新统计请点击「重新匹配流水」');
    dialogVisible.value = false;
    loadData();
  }).finally(() => {
    saving.value = false;
  });
};

const handleDelete = (row: LendingPlatform) => {
  if (row.builtin) {
    ElMessage.warning('「其他平台」是系统内置兜底项，不能删除；可停用或修改关键词');
    return;
  }
  ElMessageBox.confirm(`确定删除平台「${row.name}」吗？历史命中记录会在下次重新匹配后清除。`, '提示', {
    type: 'warning',
  }).then(() => {
    deletePlatform(row.id).then(() => {
      ElMessage.success('删除成功');
      loadData();
    });
  }).catch(() => {
  });
};

const handleToggle = (row: LendingPlatform, enabled: boolean) => {
  setPlatformEnabled(row.id, enabled).then(() => {
    row.enabled = enabled;
    ElMessage.success(enabled ? '已启用' : '已停用');
  }).catch(() => {
    loadData();
  });
};

const handleRefresh = () => {
  refreshing.value = true;
  initPlatforms().then((res) => {
    const r = res.data;
    ElMessage.success(`初始化完成：新增 ${r.inserted} 个，跳过已存在 ${r.skipped} 个，当前共 ${r.total} 个平台`);
    loadData();
  }).finally(() => {
    refreshing.value = false;
  });
};

const handleMatch = () => {
  matching.value = true;
  refreshMatches().then((res) => {
    const r = res.data;
    ElMessage.success(
      `匹配完成：扫描 ${r.scannedCount} 笔，命中 ${r.matchedCount} 笔，命中率 ${r.hitRate}%，耗时 ${r.elapsedMs} ms`
    );
  }).finally(() => {
    matching.value = false;
  });
};

onMounted(loadData);
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

.handle-input {
  width: 220px;
}

.handle-select {
  width: 120px;
}

.table {
  width: 100%;
}

.keyword-tag {
  margin: 2px 6px 2px 0;
}

.builtin-tag {
  margin-left: 6px;
}

.page-tip {
  font-size: 12px;
  color: #909399;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 12px;
  line-height: 1.6;
}

.form-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
  margin-top: 4px;
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

  .handle-input,
  .handle-select {
    width: 100%;
  }
}
</style>
