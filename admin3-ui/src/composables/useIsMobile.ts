import {ref} from 'vue';

/**
 * 全局共享的窄屏（移动端）状态。
 * 断点与 main.css、header.vue 中的 media query 保持一致（768px）。
 */
const query = window.matchMedia('(max-width: 768px)');
const isMobile = ref(query.matches);

query.addEventListener('change', (e) => {
	isMobile.value = e.matches;
});

export function useIsMobile() {
	return isMobile;
}
