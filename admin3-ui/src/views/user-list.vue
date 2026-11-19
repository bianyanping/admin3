<template>
  <div class="user-page">
    <div class="container user-layout">
      <div class="org-col">
        <div class="org-panel">
            <div style="margin-bottom: 24px; font-weight: 700">组织架构</div>
            <el-divider></el-divider>
            <el-tree
              :data="treeData"
              node-key="id"
              :current-node-key="selectedNode?.id"
              :highlight-current="true"
              :expand-on-click-node="false"
              :indent="14"
              :default-expanded-keys="defaultExpandedKeys"
              @node-click="handleNodeSelected"
              @node-expand="handleNodeExpand"
              ref="treeRef"
            >
              <template #default="{ node, data }">
              <span class="custom-tree-node">
                <el-icon v-if="data.type === OrgTypeEum.DEPART"><office-building/></el-icon>
                <el-icon v-else><SuitcaseLine/></el-icon>
                <span style="margin: 0 4px">{{ data.name }}</span>
                <el-dropdown trigger="hover">
                  <el-icon @click.stop><setting/></el-icon>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <span v-action:organization:create>
                        <el-dropdown-item
                          @click.stop="handleAddOrgNode(data)">新增</el-dropdown-item>
                      </span>
                      <span v-if="data.id!==1" v-action:organization:create>
                        <el-dropdown-item
                          @click.stop="handleUpdateOrgNode(data,node?.parent?.data)"
                        >
                        编辑
                      </el-dropdown-item>
                      </span>
                      <span v-if="data.id!=1" v-action:organization:delete>
                        <el-dropdown-item @click.stop="saveDeleteOrgNode(data)">
                        <span style="color: #f56c6c">删除</span>
                      </el-dropdown-item>
                      </span>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </span>
              </template>
            </el-tree>
        </div>
      </div>
      <div class="user-col">
            <div class="handle-box">
              <el-input v-model="query.username" placeholder="用户名" class="handle-input" clearable></el-input>
              <el-select v-model="query.state" placeholder="状态" class="handle-select" clearable>
                <el-option key="1" label="正常" value="NORMAL"></el-option>
                <el-option key="2" label="锁定" value="LOCKED"></el-option>
              </el-select>
              <el-select v-model="query.expired" placeholder="过期状态" class="handle-select" clearable>
                <el-option key="1" label="已过期" value="true"></el-option>
                <el-option key="2" label="未过期" value="false"></el-option>
              </el-select>
              <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
              <el-button type="primary" :icon="Plus" @click="openAddUser"
                         v-action:user:create>新增
              </el-button>
            </div>
            <div v-if="!isMobile" ref="tableWrap" class="table-wrap">
            <el-table :data="tableData" :height="tableHeight" border class="table" ref="multipleTable" header-cell-class-name="table-header">
              <el-table-column prop="id" label="ID" width="55" align="center"></el-table-column>
              <el-table-column prop="username" label="用户名"></el-table-column>
              <el-table-column prop="gender" label="性别">
                <template #default="{ row }">
                  <span>{{ row.gender === 'MALE' ? '男' : '女' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="头像" align="center">
                <template #default="scope">
                  <el-image
                    class="table-td-thumb"
                    :src="scope.row.avatar || 'avatar.jpg'"
                    :z-index="10"
                    :preview-src-list="[scope.row.avatar || 'avatar.jpg']"
                    preview-teleported
                  >
                  </el-image>
                </template>
              </el-table-column>
              <el-table-column label="状态" align="center">
                <template #default="scope">
                  <el-tag
                    :type="scope.row.state === 'NORMAL' ? 'success' : scope.row.state === 'LOCKED' ? 'danger' : ''"
                  >
                    {{ scope.row.state === 'NORMAL' ? '正常' : scope.row.state === 'LOCKED' ? '锁定' : '' }}
                  </el-tag>
                </template>
              </el-table-column>

              <el-table-column prop="createdTime" label="注册时间"></el-table-column>
              <el-table-column label="过期时间">
                <template #default="{ row }">
                  <span :class="isExpired(row.expireTime) ? 'red' : ''">
                    {{ formatExpireTime(row.expireTime) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="所属组织">
                <template #default="{ row }">{{ lastOrgName(row.orgFullName) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" fixed="right" align="center">
                <template #default="scope">
                  <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, scope.row, scope.$index)">
                    <el-button text type="primary">
                      操作<el-icon class="el-icon--right"><ArrowDown /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <span v-action:user:update>
                          <el-dropdown-item v-if="scope.row.state==='NORMAL'" command="disable" :icon="Lock">
                            禁用
                          </el-dropdown-item>
                        </span>
                        <span v-action:user:update>
                          <el-dropdown-item v-if="scope.row.state==='LOCKED'" command="enable" :icon="Unlock">
                            启用
                          </el-dropdown-item>
                        </span>
                        <span v-action:user:update>
                          <el-dropdown-item command="edit" :icon="Edit">编辑</el-dropdown-item>
                        </span>
                        <span v-action:user:update>
                          <el-dropdown-item command="resetPassword">重置密码</el-dropdown-item>
                        </span>
                        <span v-action:user:delete>
                          <el-dropdown-item command="delete" :icon="Delete" divided>
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
            <div v-else class="user-cards">
              <div v-for="(row, idx) in tableData" :key="row.id" class="user-card">
                <div class="uc-head">
                  <el-image
                    class="uc-avatar"
                    :src="row.avatar || 'avatar.jpg'"
                    :preview-src-list="[row.avatar || 'avatar.jpg']"
                    preview-teleported
                    fit="cover"
                  ></el-image>
                  <div class="uc-body">
                    <div class="uc-name">
                      <span>{{ row.username }}</span>
                      <el-tag
                        size="small"
                        :type="row.state === 'NORMAL' ? 'success' : row.state === 'LOCKED' ? 'danger' : ''"
                      >
                        {{ row.state === 'NORMAL' ? '正常' : row.state === 'LOCKED' ? '锁定' : '' }}
                      </el-tag>
                    </div>
                    <div class="uc-sub">ID {{ row.id }} · {{ row.gender === 'MALE' ? '男' : '女' }}</div>
                    <div class="uc-sub">{{ lastOrgName(row.orgFullName) || '未分配组织' }}</div>
                    <div class="uc-sub">过期时间：{{ formatExpireTime(row.expireTime) }}</div>
                    <div class="uc-sub">{{ row.createdTime }}</div>
                  </div>
                </div>
                <div class="uc-actions">
                  <el-dropdown trigger="click" @command="(cmd: string) => handleCommand(cmd, row, idx)">
                    <el-button text type="primary" size="small">
                      操作<el-icon class="el-icon--right"><ArrowDown /></el-icon>
                    </el-button>
                    <template #dropdown>
                      <el-dropdown-menu>
                        <span v-action:user:update>
                          <el-dropdown-item v-if="row.state==='NORMAL'" command="disable" :icon="Lock">
                            禁用
                          </el-dropdown-item>
                        </span>
                        <span v-action:user:update>
                          <el-dropdown-item v-if="row.state==='LOCKED'" command="enable" :icon="Unlock">
                            启用
                          </el-dropdown-item>
                        </span>
                        <span v-action:user:update>
                          <el-dropdown-item command="edit" :icon="Edit">编辑</el-dropdown-item>
                        </span>
                        <span v-action:user:update>
                          <el-dropdown-item command="resetPassword">重置密码</el-dropdown-item>
                        </span>
                        <span v-action:user:delete>
                          <el-dropdown-item command="delete" :icon="Delete" divided>
                            <span style="color: #f56c6c">删除</span>
                          </el-dropdown-item>
                        </span>
                      </el-dropdown-menu>
                    </template>
                  </el-dropdown>
                </div>
              </div>
            </div>
            <div class="pagination">
              <el-pagination
                background
                :small="isMobile"
                layout="total, prev, pager, next"
                :current-page="query.pageIndex"
                :page-size="query.pageSize"
                :total="pageTotal"
                @current-change="handlePageChange"
              ></el-pagination>
            </div>
      </div>
    </div>

    <!-- 新增组织架构树 -->
    <el-dialog title="新增" v-model="addOrgDialogVisible" width="min(480px, 92vw)">
      <el-form label-width="70px">
        <el-form-item label="上级节点">
          <el-input v-model="orgForm.parentName" disabled></el-input>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="orgForm.name"></el-input>
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="orgForm.type">
            <el-radio :label="OrgTypeEum.DEPART">部门</el-radio>
            <el-radio :label="OrgTypeEum.JOB">岗位</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
				<span class="dialog-footer">
					<el-button @click="addOrgDialogVisible = false">取 消</el-button>
					<el-button type="primary" @click="saveAddOrgNode">确 定</el-button>
				</span>
      </template>
    </el-dialog>

    <!-- 编辑组织架构树 -->
    <el-dialog title="编辑" v-model="updateOrgDialogVisible" width="min(480px, 92vw)">
      <el-form label-width="70px">
        <el-form-item label="上级节点">
          <el-input v-model="orgForm.parentName" disabled></el-input>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="orgForm.name"></el-input>
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-radio-group v-model="orgForm.type" disabled>
            <el-radio :label="OrgTypeEum.DEPART">部门</el-radio>
            <el-radio :label="OrgTypeEum.JOB">岗位</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
				<span class="dialog-footer">
					<el-button @click="updateOrgDialogVisible = false">取 消</el-button>
					<el-button type="primary" @click="saveUpdateOrgNode">确 定</el-button>
				</span>
      </template>
    </el-dialog>

    <!-- 新增用户弹出框 -->
    <el-dialog title="新增" v-model="addVisible" width="min(480px, 92vw)">
      <el-form label-width="70px">
        <el-form-item label="所属组织">
          <el-input :model-value="selectedNode?.name || ''" disabled></el-input>
        </el-form-item>
        <el-input v-model="form.avatar" hidden="hidden" v-show="false"></el-input>
        <el-form-item label="用户名">
          <el-input v-model="form.username"></el-input>
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio label="MALE">男</el-radio>
            <el-radio label="FEMALE">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            placeholder="不填表示永不过期"
            value-format="YYYY-MM-DD HH:mm:ss"
            clearable
            style="width: 100%"
          ></el-date-picker>
        </el-form-item>
        <el-form-item label="头像">
          <AvatarUpload :img-src="form.avatar" @on-select="onAvatarSelect"/>
        </el-form-item>
      </el-form>
      <template #footer>
				<span class="dialog-footer">
					<el-button @click="addVisible = false">取 消</el-button>
					<el-button type="primary" @click="saveAdd">确 定</el-button>
				</span>
      </template>
    </el-dialog>
    <!-- 编辑用户弹出框 -->
    <el-dialog title="编辑" v-model="editVisible" width="min(480px, 92vw)">
      <el-form label-width="70px">
        <el-form-item label="所属组织">
          <el-input :model-value="selectedNode?.name || ''" disabled></el-input>
        </el-form-item>
        <el-input v-model="form.avatar" hidden="hidden" v-show="false"></el-input>
        <el-form-item label="用户名">
          <el-input v-model="form.username" disabled></el-input>
        </el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio label="MALE">男</el-radio>
            <el-radio label="FEMALE">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker
            v-model="form.expireTime"
            type="datetime"
            placeholder="不填表示永不过期"
            value-format="YYYY-MM-DD HH:mm:ss"
            clearable
            style="width: 100%"
          ></el-date-picker>
        </el-form-item>
        <el-form-item label="头像">
          <AvatarUpload :img-src="form.avatar" @on-select="onAvatarSelect"/>
        </el-form-item>
      </el-form>
      <template #footer>
				<span class="dialog-footer">
					<el-button @click="editVisible = false">取 消</el-button>
					<el-button type="primary" @click="saveEdit">确 定</el-button>
				</span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {reactive, ref} from 'vue';
import {ElMessage, ElMessageBox} from 'element-plus';
import {useIsMobile} from '../composables/useIsMobile';
import {useFillHeight} from '../composables/useFillHeight';
import {ArrowDown, Delete, Edit, Lock, Plus, Search, Unlock} from '@element-plus/icons-vue';
import {createUser, deleteUser, disableUser, enableUser, resetPassword, updateUser} from "../api/user";
import {
  createOrganization,
  deleteOrganization,
  getOrganizationTree,
  getOrganizationUserList,
  updateOrganization
} from "../api/organization";
import AvatarUpload from "../components/AvatarUpload.vue";

interface OrgTreeNode {
  name: string
  id: number
  children?: OrgTreeNode[]
  type: OrgTypeEum
  isLeaf?: boolean
}

interface OrgSelectableTreeNode extends Partial<Omit<OrgTreeNode, 'children'>> {
  label: string
  value: number
  children?: OrgSelectableTreeNode[] | OrgTreeNode[]
}

enum OrgTypeEum {
  DEPART = 'DEPARTMENT',
  JOB = 'JOB',
}

const treeData = ref<OrgTreeNode[]>([]);
const defaultExpandedKeys = ref<number[]>([]);
const selectedNode = ref<OrgTreeNode | null>(null);

/** 当前生效的组织节点：优先选中节点，其次树根（均由后端按权限范围返回） */
const currentOrgNode = (): OrgTreeNode | null => selectedNode.value || treeData.value[0] || null;

const handleNodeSelected = (node: OrgTreeNode) => {
  selectedNode.value = node;
  getUserData();
}

/** 所属组织只展示最末级名称 */
const lastOrgName = (fullName?: string) => (fullName ? fullName.split('-').pop() : '');

/** 过期时间：为空表示永不过期 */
const formatExpireTime = (expireTime?: string | null) => (expireTime ? expireTime.replace('T', ' ') : '永不过期');

/** 是否已过期（兼容后端 yyyy-MM-dd HH:mm:ss 格式） */
const isExpired = (expireTime?: string | null) => {
  if (!expireTime) {
    return false;
  }
  const time = new Date(expireTime.replace(' ', 'T')).getTime();
  return !Number.isNaN(time) && time <= Date.now();
};

/** 新增用户：默认归属到当前选中的组织 */
const openAddUser = () => {
  const node = currentOrgNode();
  if (!node) {
    ElMessage.warning('暂无可用的组织节点');
    return;
  }
  Object.assign(form, new User());
  form.organizationId = node.id;
  addVisible.value = true;
};

const handleNodeExpand = (d: any) => {
  // console.log(d, 'expand')
}
const reqAllNodes = async () => {
  const res = await getOrganizationTree({
    parentId: 1
  })
  treeData.value = res.data || [];
  convertOrgTree(treeData.value);

  const selected = currentOrgNode();
  if (!selected || !treeData.value.some((item) => item.id === selected.id)) {
    selectedNode.value = treeData.value[0] || null;
  }
  defaultExpandedKeys.value = selectedNode.value ? [selectedNode.value.id] : [];
  getUserData();
}
reqAllNodes();


class Org {
  id = 0;
  name = '';
  type = OrgTypeEum.DEPART;
  parentId = 1;
  parentName = '';
}

let orgForm = reactive(new Org());

//表格新增时
const addOrgDialogVisible = ref(false);

const updateOrgDialogVisible = ref(false);

const handleAddOrgNode = (data: OrgTreeNode) => {
  Object.assign(orgForm, new Org());
  orgForm.parentName = data.name;
  orgForm.parentId = data.id;
  addOrgDialogVisible.value = true;
}

const handleUpdateOrgNode = (data: OrgTreeNode, parent?: OrgTreeNode) => {
  orgForm.id = data.id;
  orgForm.name = data.name;
  orgForm.type = data.type;
  orgForm.parentId = parent?.id ?? data.id;
  orgForm.parentName = parent?.name ?? '';
  updateOrgDialogVisible.value = true;
}

const saveAddOrgNode = async () => {
  await createOrganization(orgForm);
  addOrgDialogVisible.value = false;
  await reqAllNodes();
}

/**
 * @desc 转换数组对象的键值对 el-tree => el-tree-select
 */
const convertOrgTree = (orgTreeNode: Partial<(OrgTreeNode & { value: number, label: string })>[]) => {
  if (!orgTreeNode.length) {
    return;
  }

  for (let org of orgTreeNode) {
    org.value = org.id as number;
    org.label = org.name as string;

    if (org.children?.length) {
      convertOrgTree(org.children);
    }
  }
}

const saveUpdateOrgNode = async () => {
  await updateOrganization(orgForm.id, orgForm);
  updateOrgDialogVisible.value = false;
  await reqAllNodes();
}

const saveDeleteOrgNode = async (data: OrgTreeNode) => {
  // 二次确认删除
  ElMessageBox.confirm('确定要删除吗？', '提示', {
    type: 'warning'
  }).then(() => {
    deleteOrganization(data.id).then(res => {
      ElMessage.success('删除成功');
      reqAllNodes();
    });
  }).catch(() => {
  });

}

interface TableItem {
  id: number;
  username: string;
  gender: string;
  avatar: string;
  state: string;
  createdTime: string;
  expireTime: string | null;
  orgFullName: string;
}

const query = reactive({
  username: '',
  state: '',
  expired: '',
  pageIndex: 1,
  pageSize: 10
});
const tableData = ref<TableItem[]>([]);
const pageTotal = ref(0);
// 获取表格数据
const getUserData = () => {
  const node = currentOrgNode();
  if (!node) {
    tableData.value = [];
    pageTotal.value = 0;
    return;
  }
  getOrganizationUserList(node.id, {
    page: query.pageIndex,
    size: query.pageSize,
    username: query.username || undefined,
    state: query.state || undefined,
    expired: query.expired || undefined,
  }).then(res => {
    tableData.value = res.data.list;
    pageTotal.value = res.data.total;
  });
};
getUserData();

// 查询操作
const isMobile = useIsMobile();
// 桌面端：用户表格高度跟随容器，数据多时表格内部滚动，页面不出现上下滚动条
const tableWrap = ref<HTMLElement | null>(null);
const {height: tableHeight} = useFillHeight(tableWrap);

const handleSearch = () => {
  query.pageIndex = 1;
  getUserData();
};
// 分页导航
const handlePageChange = (val: number) => {
  query.pageIndex = val;
  getUserData();
};

// 删除操作
const handleDelete = (row: any) => {
  // 二次确认删除
  ElMessageBox.confirm('确定要删除吗？', '提示', {
    type: 'warning'
  }).then(() => {
    deleteUser(row.id).then(res => {
      getUserData();
    });
    ElMessage.success('删除成功');
  })
    .catch(() => {
    });
};

//表格新增时
const addVisible = ref(false);

// 表格编辑时弹窗和保存
const editVisible = ref(false);

class User {
  username = '';
  gender = 'MALE';
  avatar = '';
  organizationId = 1;
  expireTime: string | null = null;
}

let id: number = 0;
let form = reactive(new User());

const saveAdd = () => {
  const node = currentOrgNode();
  if (!node) {
    ElMessage.warning('请先选择组织节点');
    return;
  }
  form.organizationId = node.id;
  form.expireTime = form.expireTime || null;
  createUser(form).then(res => {
    getUserData();
    ElMessage.success(`新增成功`);
    addVisible.value = false;
  });
};
const handleEdit = (index: number, row: any) => {
  id = row.id;
  form.username = row.username;
  form.gender = row.gender;
  form.avatar = row.avatar;
  form.expireTime = row.expireTime || null;
  editVisible.value = true;
};
const saveEdit = () => {
  editVisible.value = false;
  const node = currentOrgNode();
  if (!node) {
    ElMessage.warning('请先选择组织节点');
    return;
  }
  form.organizationId = node.id;
  form.expireTime = form.expireTime || null;
  updateUser(id, form).then(res => {
    getUserData();
    ElMessage.success(`修改成功`);
  });
};
const handleDisable = (row: any) => {
  disableUser(row.id).then(res => {
    getUserData();
    ElMessage.success(`禁用成功`);
  });
}
const handleEnable = (row: any) => {
  enableUser(row.id).then(res => {
    getUserData();
    ElMessage.success(`启用成功`);
  });
}
const handleResetPassword = (row: any) => {
  resetPassword(row.id).then(() => {
    ElMessage.success(`密码已重置为 123456`);
  });
}
// 操作列下拉菜单
const handleCommand = (cmd: string, row: any, index: number) => {
  switch (cmd) {
    case 'disable':
      ElMessageBox.confirm('确定要禁用该用户吗？', '提示', {type: 'warning'})
        .then(() => handleDisable(row))
        .catch(() => {
        });
      break;
    case 'enable':
      ElMessageBox.confirm('确定要启用该用户吗？', '提示', {type: 'warning'})
        .then(() => handleEnable(row))
        .catch(() => {
        });
      break;
    case 'edit':
      handleEdit(index, row);
      break;
    case 'resetPassword':
      ElMessageBox.confirm('确定将密码重置为 123456 吗？', '提示', {type: 'warning'})
        .then(() => handleResetPassword(row))
        .catch(() => {
        });
      break;
    case 'delete':
      handleDelete(row);
      break;
  }
}
const onAvatarSelect = (imgUrl: string) => {
  form.avatar = imgUrl;
}

</script>

<style scoped>
/* 两栏固定布局：左侧组织架构窄栏，右侧用户列表占满剩余高度 */
.user-page { height: 100%; }
.user-layout {
  height: 100%;
  box-sizing: border-box;
  display: flex;
  align-items: stretch;
}
.org-col {
  flex: 0 0 200px;
  width: 200px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid #dcdfe6;
}
.org-panel {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding-right: 8px;
}
/* 节点名过长时省略，避免把面板撑宽 */
.custom-tree-node {
  display: flex;
  align-items: center;
  width: 100%;
  min-width: 0;
  overflow: hidden;
}
.custom-tree-node > span:not(.el-dropdown) {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.custom-tree-node :deep(.el-dropdown) { flex: none; }
.user-col {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  padding-left: 10px;
  display: flex;
  flex-direction: column;
}
.table-wrap { flex: 1 1 auto; min-height: 0; }
.pagination { flex: none; }

.handle-box {
  flex: none;
  margin-bottom: 20px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.handle-box > * {
  margin: 0;
}

.handle-select {
  width: 120px;
}

.handle-input {
  width: 300px;
}

.table {
  width: 100%;
  font-size: 14px;
}

.red {
  color: #F56C6C;
}

.mr10 {
  margin-right: 10px;
}

.table-td-thumb {
  display: block;
  margin: auto;
  width: 40px;
  height: 40px;
}

/* ===== 窄屏卡片式用户列表 ===== */
.user-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.user-card {
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}

.uc-head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.uc-avatar {
  width: 44px;
  height: 44px;
  border-radius: 50%;
  flex: none;
  background: #f5f7fa;
}

.uc-body {
  flex: 1;
  min-width: 0;
}

.uc-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 15px;
  margin-bottom: 4px;
}

.uc-sub {
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
  word-break: break-all;
}

.uc-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}

.uc-actions :deep(.el-button) {
  padding: 4px 6px;
  margin-left: 0;
}

@media (max-width: 768px) {
  .user-page { height: auto; }
  .user-layout { height: auto; display: block; }
  .org-col {
    width: auto;
    flex: none;
    border-right: none;
  }
  .org-panel {
    max-height: 220px;
    overflow-y: auto;
    margin-bottom: 12px;
    padding-right: 0 !important;
  }
  .user-col { padding-left: 0; display: block; }

  .handle-input,
  .handle-select {
    width: 100%;
  }
}
</style>
