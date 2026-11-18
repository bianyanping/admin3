import {onBeforeUnmount, onMounted, ref, watch, type Ref} from 'vue';

/**
 * 把容器高度同步给 el-table 的 height，让表格在容器内滚动。
 * 这样页面外层不出现滚动条，表头、筛选栏、分页始终固定可见。
 */
export function useFillHeight(target: Ref<any>, min = 180) {
	const height = ref<number>();
	let observer: ResizeObserver | null = null;

	const measure = () => {
		const el = target.value;
		if (!el) return;
		height.value = Math.max(Math.round(el.clientHeight), min);
	};

	const observe = () => {
		observer?.disconnect();
		observer = null;
		if (target.value && typeof ResizeObserver !== 'undefined') {
			observer = new ResizeObserver(measure);
			observer.observe(target.value);
		}
		measure();
	};

	onMounted(() => {
		observe();
		window.addEventListener('resize', measure);
	});
	watch(target, () => observe(), {flush: 'post'});
	onBeforeUnmount(() => {
		observer?.disconnect();
		window.removeEventListener('resize', measure);
	});

	return {height};
}
