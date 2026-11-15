import Taro from '@tarojs/taro';
import { API_BASE, TOKEN_KEY } from '@/config';

export const getToken = (): string => {
  try {
    return Taro.getStorageSync(TOKEN_KEY) || '';
  } catch (err) {
    console.error('[Request] 读取 token 失败', err);
    return '';
  }
};

export const setToken = (token: string) => {
  try {
    Taro.setStorageSync(TOKEN_KEY, token);
  } catch (err) {
    console.error('[Request] 写入 token 失败', err);
  }
};

export const clearToken = () => {
  try {
    Taro.removeStorageSync(TOKEN_KEY);
  } catch (err) {
    console.error('[Request] 清除 token 失败', err);
  }
};

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  data?: Record<string, any>;
}

/** 带会话 token 的后端请求封装，错误统一抛出 message */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const token = getToken();
  const data = options.data
    ? Object.keys(options.data).reduce<Record<string, any>>((acc, key) => {
        const value = options.data![key];
        if (value !== undefined && value !== null) {
          acc[key] = value;
        }
        return acc;
      }, {})
    : undefined;
  const response = await Taro.request({
    url: `${API_BASE}${path}`,
    method: options.method || 'GET',
    data,
    header: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  });

  if (response.statusCode === 401) {
    clearToken();
    throw new Error('登录已过期，请重新登录');
  }
  if (response.statusCode >= 400) {
    const message = (response.data as { message?: string })?.message;
    throw new Error(message || `请求失败（${response.statusCode}）`);
  }
  return response.data as T;
}
