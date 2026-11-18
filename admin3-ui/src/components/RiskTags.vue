<template>
  <!-- 用原生元素渲染标签：避免在 el-table 展开行/插槽中混入组件 vnode 造成生产构建补丁错位 -->
  <span class="risk-tags">
    <span
      v-for="tag in tags || []"
      :key="tag.id + '-' + tag.source"
      class="risk-tag"
      :class="['risk-tag--' + (tag.color || 'info'), {'risk-tag--system': tag.source === 'SYSTEM'}]"
      :title="tag.source === 'SYSTEM' ? '系统规则标记' : '手工标记'"
    >
      {{ tag.name }}<span v-if="tag.source === 'SYSTEM'" class="tag-source">系统</span>
    </span>
  </span>
</template>

<script setup lang="ts">
import type {RiskTagItem} from '../composables/useRiskTags';

defineProps<{
  tags?: RiskTagItem[];
}>();
</script>

<style scoped>
.risk-tags {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 3px;
  vertical-align: middle;
}

.risk-tag {
  display: inline-flex;
  align-items: center;
  height: 18px;
  padding: 0 5px;
  border: 1px solid transparent;
  border-radius: 3px;
  font-size: 11px;
  line-height: 16px;
  white-space: nowrap;
}

.risk-tag--danger {
  color: #f56c6c;
  background: #fef0f0;
  border-color: #fde2e2;
}

.risk-tag--warning {
  color: #e6a23c;
  background: #fdf6ec;
  border-color: #faecd8;
}

.risk-tag--success {
  color: #67c23a;
  background: #f0f9eb;
  border-color: #e1f3d8;
}

.risk-tag--primary {
  color: #409eff;
  background: #ecf5ff;
  border-color: #d9ecff;
}

.risk-tag--info {
  color: #909399;
  background: #f4f4f5;
  border-color: #e9e9eb;
}

/* 系统规则标签：虚线边框，与手工标签区分 */
.risk-tag--system {
  border-style: dashed;
}

.tag-source {
  margin-left: 2px;
  font-size: 10px;
  opacity: .7;
}
</style>
