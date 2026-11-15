import {defineConfig} from 'vite';
import vue from '@vitejs/plugin-vue';
import VueSetupExtend from 'vite-plugin-vue-setup-extend';
import AutoImport from 'unplugin-auto-import/vite';
import Components from 'unplugin-vue-components/vite';
import {ElementPlusResolver} from 'unplugin-vue-components/resolvers';

export default defineConfig({
	base: './',
	plugins: [
		vue(),
		VueSetupExtend(),
		AutoImport({
			resolvers: [ElementPlusResolver()]
		}),
		Components({
			resolvers: [ElementPlusResolver()]
		})
	],
	server: {
		host: '0.0.0.0',
		proxy: {
			'/admin3': {
				target: `http://localhost:8080`,
			},
			// 头像等文件访问地址为 storage/fetch/xxx（不带 /admin3 前缀），开发环境需要代理
			'/storage': {
				target: `http://localhost:8080`,
				rewrite: (p) => '/admin3' + p,
			},
		},
	},
	optimizeDeps: {
		include: ['schart.js']
	}
});
