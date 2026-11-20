import {createRouter, createWebHashHistory, RouteRecordRaw} from 'vue-router';
import Home from '../views/home.vue';
import {useBasicStore} from "../store/basic";

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/dashboard',
  },
  {
    path: '/',
    name: 'Home',
    component: Home,
    children: [
      {
        path: '/dashboard',
        name: 'dashboard',
        meta: {
          title: '系统首页',
        },
        component: () => import(/* webpackChunkName: "dashboard" */ '../views/dashboard.vue'),
      },
      {
        path: '/users',
        name: 'users',
        meta: {
          title: '用户列表',
        },
        component: () => import(/* webpackChunkName: "dashboard" */ '../views/user-list.vue'),
      }, {
        path: '/roles',
        name: 'roles',
        meta: {
          title: '角色管理',
        },
        component: () => import(/* webpackChunkName: "dashboard" */ '../views/role-list.vue'),
      }, {
        path: '/resources',
        name: 'resources',
        meta: {
          title: '权限资源',
        },
        component: () => import(/* webpackChunkName: "dashboard" */ '../views/resource-list.vue'),
      },
      {
        path: '/logs',
        name: 'logs',
        meta: {
          title: '系统日志',
        },
        component: () => import(/* webpackChunkName: "dashboard" */ '../views/log-list.vue'),
      },
      {
        path: '/storage',
        name: 'storage',
        meta: {
          title: '对象存储',
        },
        component: () => import(/* webpackChunkName: "dashboard" */ '../views/storage-list.vue'),
      },
      {
        path: '/bank-statements',
        name: 'bankStatements',
        meta: {
          title: '银行流水',
        },
        component: () => import(/* webpackChunkName: "bankStatement" */ '../views/bank-statement-list.vue'),
      },
      {
        path: '/bank-statement-analysis',
        name: 'bankStatementAnalysis',
        meta: {
          title: '流水单分析',
        },
        component: () => import(/* webpackChunkName: "bankStatement" */ '../views/bank-statement-analysis.vue'),
      },
      {
        path: '/bank-statements/:id/transactions',
        name: 'bankStatementTransactions',
        meta: {
          title: '流水明细',
        },
        component: () => import(/* webpackChunkName: "bankStatement" */ '../views/bank-statement-transactions.vue'),
      },
      {
        path: '/lending-analysis',
        name: 'lendingAnalysis',
        meta: {
          title: '网贷分析',
        },
        component: () => import(/* webpackChunkName: "lending" */ '../views/lending-analysis.vue'),
      },
      {
        path: '/lending-platforms',
        name: 'lendingPlatforms',
        meta: {
          title: '网贷关键词库',
        },
        component: () => import(/* webpackChunkName: "lending" */ '../views/lending-platforms.vue'),
      },
      {
        path: '/risk-database',
        name: 'riskDatabase',
        meta: {
          title: '风险数据库',
          permissions: ['risk:funder:view', 'risk:blacklist:view', 'risk:counterparty:view'],
        },
        component: () => import(/* webpackChunkName: "risk" */ '../views/risk-database.vue'),
      },
      {
        path: '/tabs',
        name: 'tabs',
        meta: {
          title: 'tab标签',
          permiss: '3',
        },
        component: () => import(/* webpackChunkName: "tabs" */ '../views/tabs.vue'),
      },
      {
        path: '/user',
        name: 'user',
        meta: {
          title: '个人中心',
        },
        component: () => import(/* webpackChunkName: "user" */ '../views/user.vue'),
      },
    ],
  },
  {
    path: '/login',
    name: 'Login',
    meta: {
      title: '登录',
    },
    component: () => import(/* webpackChunkName: "login" */ '../views/login.vue'),
  },
  {
    path: '/403',
    name: '403',
    meta: {
      title: '没有权限',
    },
    component: () => import(/* webpackChunkName: "403" */ '../views/403.vue'),
  },
];

const router = createRouter({
  history: createWebHashHistory(),
  routes,
});

router.beforeEach(async (to, from, next) => {
  document.title = `${to.meta.title} | 账单流水分析系统`;
  const token = localStorage.getItem('token');
  const basicStore = useBasicStore();
  if (!token && to.path !== '/login') {
    next('/login');
    return;
  }
  if (token) {
    await basicStore.fetchUserinfo();
  }
  // 页面级权限：要求命中任一权限（超级管理员 * 直接放行）
  const required = to.meta.permissions as string[] | undefined;
  if (required && required.length > 0) {
    const permissions: string[] = (basicStore.userinfo?.permissions as any) || [];
    const allowed = permissions.includes('*') || required.some((permission) => permissions.includes(permission));
    if (!allowed) {
      next('/403');
      return;
    }
  }
  next();
});
export default router;
