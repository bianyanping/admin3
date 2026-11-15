import {defineStore} from 'pinia';

export const useSidebarStore = defineStore('sidebar', {
	state: () => {
		return {
			collapse: false,
			// 是否处于移动端（窄屏）：此时侧边栏以抽屉形式覆盖在内容之上
			isMobile: false
		};
	},
	getters: {},
	actions: {
		handleCollapse() {
			this.collapse = !this.collapse;
		},
		setMobile(isMobile: boolean) {
			if (this.isMobile === isMobile) return;
			this.isMobile = isMobile;
			// 进入移动端时收起抽屉，回到桌面端时恢复展开
			this.collapse = isMobile;
		}
	}
});
