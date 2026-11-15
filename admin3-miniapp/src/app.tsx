import React, { useEffect } from 'react';
import { useDidShow, useDidHide } from '@tarojs/taro';
import './app.scss';
import { login } from './services/api';
import { useAuthStore } from './store/auth';

function App(props) {
  useEffect(() => {
    // 数据已改为直连后台接口，不再依赖云开发
    const { restore, setProfile } = useAuthStore.getState();
    restore();
    if (!useAuthStore.getState().profile) {
      login()
        .then((result) => setProfile(result.user))
        .catch((err) => console.error('[App] 自动登录失败', err));
    }
  }, []);

  useDidShow(() => {});

  useDidHide(() => {});

  return props.children;
}

export default App;
