import { create } from 'zustand';
import Taro from '@tarojs/taro';
import type { UserProfile } from '@/types';

const STORAGE_KEY = 'BANK_ANALYSIS_PROFILE';

interface AuthState {
  profile: UserProfile | null;
  /** 从本地缓存恢复登录态 */
  restore: () => void;
  /** 设置并持久化登录用户 */
  setProfile: (profile: UserProfile) => void;
  /** 退出登录 */
  clear: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  profile: null,

  restore: () => {
    try {
      const cached = Taro.getStorageSync(STORAGE_KEY);
      if (cached) {
        set({ profile: cached as UserProfile });
      }
    } catch (err) {
      console.error('[Auth] 读取本地登录态失败', err);
    }
  },

  setProfile: (profile: UserProfile) => {
    try {
      Taro.setStorageSync(STORAGE_KEY, profile);
    } catch (err) {
      console.error('[Auth] 写入本地登录态失败', err);
    }
    set({ profile });
  },

  clear: () => {
    try {
      Taro.removeStorageSync(STORAGE_KEY);
    } catch (err) {
      console.error('[Auth] 清除本地登录态失败', err);
    }
    set({ profile: null });
  }
}));
