import React, { useState } from 'react';
import { Input, Text, View } from '@tarojs/components';
import Taro from '@tarojs/taro';
import styles from './index.module.scss';
import { bindAccount, login, NeedBindError } from '@/services/api';
import { useAuthStore } from '@/store/auth';

const LoginPage: React.FC = () => {
  const setProfile = useAuthStore((state) => state.setProfile);
  const [loading, setLoading] = useState(false);
  const [needBind, setNeedBind] = useState(false);
  const [openid, setOpenid] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const goHome = () => {
    Taro.showToast({ title: '登录成功', icon: 'success' });
    setTimeout(() => {
      Taro.switchTab({ url: '/pages/home/index' });
    }, 500);
  };

  const handleLogin = async () => {
    if (loading) return;
    setLoading(true);
    try {
      const result = await login();
      setProfile(result.user);
      goHome();
    } catch (err) {
      if (err instanceof NeedBindError) {
        setOpenid(err.openid);
        setNeedBind(true);
        return;
      }
      console.error('[Login] 登录失败', err);
      Taro.showToast({ title: (err as Error).message || '登录失败，请重试', icon: 'none' });
    } finally {
      setLoading(false);
    }
  };

  const handleBind = async () => {
    if (loading) return;
    if (!username.trim() || !password) {
      Taro.showToast({ title: '请输入后台账号和密码', icon: 'none' });
      return;
    }
    setLoading(true);
    try {
      const result = await bindAccount(username.trim(), password);
      setProfile(result.user);
      goHome();
    } catch (err) {
      console.error('[Login] 绑定失败', err);
      Taro.showToast({ title: (err as Error).message || '绑定失败，请重试', icon: 'none' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <View className={styles.page}>
      <View className={styles.logoBox}>
        <View className={styles.logo}>
          <Text className={styles.logoText}>流</Text>
        </View>
        <Text className={styles.title}>账单流水分析系统</Text>
        <Text className={styles.subtitle}>银行流水 · 收支分析 · 异常监控</Text>
      </View>

      {needBind ? (
        <View className={styles.bindCard}>
          <Text className={styles.bindTitle}>首次使用，请绑定后台账号</Text>
          <Text className={styles.bindTip}>
            绑定后即可按你在后台的组织架构查看权限范围内的流水数据，无需重复配置。
          </Text>
          <View className={styles.field}>
            <Text className={styles.fieldLabel}>后台账号</Text>
            <Input
              className={styles.input}
              value={username}
              placeholder="请输入后台登录账号"
              onInput={(event) => setUsername(event.detail.value)}
            />
          </View>
          <View className={styles.field}>
            <Text className={styles.fieldLabel}>密码</Text>
            <Input
              className={styles.input}
              password
              value={password}
              placeholder="请输入后台登录密码"
              onInput={(event) => setPassword(event.detail.value)}
            />
          </View>
          <View className={styles.loginBtn} onClick={handleBind}>
            <Text className={styles.loginBtnText}>{loading ? '绑定中...' : '绑定并登录'}</Text>
          </View>
          <Text className={styles.bindBack} onClick={() => setNeedBind(false)}>
            返回微信登录
          </Text>
          {openid ? <Text className={styles.bindOpenid}>openid：{openid}</Text> : null}
        </View>
      ) : (
        <>
          <View className={styles.featureList}>
            <View className={styles.featureItem}>
              <Text className={styles.featureDot} />
              <Text className={styles.featureText}>多银行流水统一汇总</Text>
            </View>
            <View className={styles.featureItem}>
              <Text className={styles.featureDot} />
              <Text className={styles.featureText}>每日收支趋势与构成分析</Text>
            </View>
            <View className={styles.featureItem}>
              <Text className={styles.featureDot} />
              <Text className={styles.featureText}>大额流水实时监控提醒</Text>
            </View>
          </View>

          <View className={styles.buttonWrap}>
            <View className={styles.loginBtn} onClick={handleLogin}>
              <Text className={styles.loginBtnText}>{loading ? '登录中...' : '微信一键登录'}</Text>
            </View>
            <Text className={styles.tip}>登录后按所属组织的权限范围查看流水数据</Text>
          </View>
        </>
      )}
    </View>
  );
};

export default LoginPage;
