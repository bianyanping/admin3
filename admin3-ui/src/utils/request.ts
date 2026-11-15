import axios, {AxiosError, AxiosInstance, AxiosRequestConfig, AxiosResponse} from 'axios';
import {ElMessage} from "element-plus";
import router from "../router";

const service: AxiosInstance = axios.create({
    timeout: 5000
});

service.interceptors.request.use(
    (config: AxiosRequestConfig) => {
        const headerToken = localStorage.getItem('token');
        if (headerToken) {
            // @ts-ignore
            config.headers['Authorization'] = 'Bearer ' + headerToken
        }

        return config;
    },
    (error: AxiosError) => {
        console.log(error);
        return Promise.reject();
    }
);

service.interceptors.response.use(
    (response: AxiosResponse) => {
        if (response.status === 200) {
            return response;
        } else {
            Promise.reject();
        }
    },
    (error: AxiosError) => {
        console.log(error);
        if (error.response?.status === 401) {
            localStorage.removeItem('token');
            router.push('/login');
        }
        // silent：调用方自己处理错误（如批量上传时统一汇总提示）
        const silent = (error.config as any)?.silent;
        const data: any = error.response?.data;
        const statusText = `请求失败（${error.response?.status || '网络异常'}）`;
        if (silent) {
            return Promise.reject(error);
        }
        if (data instanceof Blob) {
            // blob 响应（下载/导出）出错时，错误体也是 Blob，读出 JSON 里的 message 再提示
            data.text().then((text: string) => {
                let msg = '';
                try {
                    msg = JSON.parse(text)?.message || '';
                } catch (e) {
                    msg = text;
                }
                ElMessage.error(msg || statusText);
            });
        } else {
            ElMessage.error(data?.message || statusText);
        }
        return Promise.reject(error);
    }
);

export default service;
