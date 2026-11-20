<template>
  <div class="sidebar" :class="{ 'sidebar-mobile-closed': sidebar.isMobile && sidebar.collapse }">
    <el-menu
        class="sidebar-el-menu"
        :default-active="onRoutes"
        :collapse="!sidebar.isMobile && sidebar.collapse"
        @select="handleMenuSelect"
        background-color="#324157"
        text-color="#bfcbd9"
        active-text-color="#20a0ff"
        unique-opened
        router
    >
      <template v-for="item in items">
        <template v-if="item.subs">
          <el-sub-menu :index="item.index" :key="item.index">
            <template #title>
              <el-icon>
                <component :is="item.icon"></component>
              </el-icon>
              <span>{{ item.title }}</span>
            </template>
            <template v-for="subItem in item.subs">
              <el-sub-menu
                  v-if="subItem.subs"
                  :index="subItem.index"
                  :key="subItem.index"
              >
                <template #title>
                  <el-icon>
                    <component :is="subItem.icon"></component>
                  </el-icon>
                  {{ subItem.title }}
                </template>
                <el-menu-item v-for="(threeItem, i) in subItem.subs" :key="i" :index="threeItem.index">
                  <el-icon>
                    <component :is="threeItem.icon"></component>
                  </el-icon>
                  {{ threeItem.title }}
                </el-menu-item>
              </el-sub-menu>
              <el-menu-item v-else :index="subItem.index">
                <el-icon>
                  <component :is="subItem.icon"></component>
                </el-icon>
                {{ subItem.title }}
              </el-menu-item>
            </template>
          </el-sub-menu>
        </template>
        <template v-else>
          <el-menu-item :index="item.index" :key="item.index">
            <el-icon>
              <component :is="item.icon"></component>
            </el-icon>
            <template #title>{{ item?.title }}</template>
          </el-menu-item>
        </template>
      </template>
    </el-menu>
  </div>
</template>

<script setup lang="ts">
import {computed, ref} from 'vue';
import {useSidebarStore} from '../store/sidebar';
import {useRoute} from 'vue-router';
import {getMenus} from "../api/resource";


interface MenuItem {
  index: string;
  icon: string;
  title: string;
  subs: MenuItem[] | undefined;
}

const items = ref<MenuItem[]>([]);

getMenus().then(res => {
  items.value = getDataNode(res.data, 1);
});

const getDataNode: any = (menus: any[], parentId: number) => {
  return menus.filter(m => m.parentId === parentId).map((menu: any) => {
    let subs: MenuItem[] = getDataNode(menus, menu.id);
    return {
      index: menu.url,
      icon: menu.icon,
      title: menu.name,
      subs: subs?.length === 0 ? undefined : subs
    }
  });
}

const route = useRoute();
const onRoutes = computed(() => {
  // 风险数据库等页面通过 ?tab= 区分模块菜单，需用 fullPath 才能正确高亮
  return route.fullPath;
});

const sidebar = useSidebarStore();

// 移动端选中菜单后自动收起抽屉
const handleMenuSelect = () => {
  if (sidebar.isMobile) {
    sidebar.collapse = true;
  }
};
</script>

<style scoped>
.sidebar {
  display: block;
  position: absolute;
  left: 0;
  top: 70px;
  bottom: 0;
  overflow-y: scroll;
}

.sidebar::-webkit-scrollbar {
  width: 0;
}

.sidebar-el-menu:not(.el-menu--collapse) {
  width: 250px;
}

.sidebar > ul {
  height: 100%;
}

/* 移动端：抽屉式侧边栏，覆盖在内容之上，由父组件遮罩配合 */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    z-index: 2001;
    background-color: #324157;
    transition: transform .3s ease-in-out;
  }

  .sidebar-mobile-closed {
    transform: translateX(-100%);
  }
}
</style>
