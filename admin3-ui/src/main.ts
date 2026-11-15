import {createApp} from 'vue';
import {createPinia} from 'pinia';
import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import App from './App.vue';
import router from './router';
import 'element-plus/dist/index.css';
import './assets/css/icon.css';
import {useBasicStore} from "./store/basic";

const app = createApp(App);
app.use(createPinia());
app.use(router);

// 注册element plus图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component);
}
const basicStore = useBasicStore();
app.directive('action', {
    mounted(el, binding) {
        const permissions: string[] = basicStore?.userinfo?.permissions || [];
        const actionName: string = binding.arg || '';
        // 超级管理员（通配权限 *）不受权限配置限制
        const isSuperAdmin = permissions.includes('*');
        if (!isSuperAdmin && permissions && !permissions.includes(actionName)) {
            el['hidden'] = true;
        }
    },
});
app.mount('#app');
