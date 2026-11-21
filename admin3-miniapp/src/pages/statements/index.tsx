import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Input, Text, View } from '@tarojs/components';
import Taro, { usePullDownRefresh, useReachBottom } from '@tarojs/taro';
import classnames from 'classnames';
import styles from './index.module.scss';
import StatementCard from '@/components/StatementCard';
import EmptyState from '@/components/EmptyState';
import { getStatements, uploadStatementPdf } from '@/services/api';
import { useRiskTags } from '@/hooks/useRiskTags';
import { useAuthStore } from '@/store/auth';
import type { Statement, StatementImportResult } from '@/types';

const PAGE_SIZE = 10;

const BANK_FILTERS = [
  { label: '全部', value: '' },
  { label: '建设银行', value: 'CCB' },
  { label: '农业银行', value: 'ABCHX' },
  { label: '邮储银行', value: 'PSBC' },
  { label: '广西农商联合银行', value: 'GXRCB' },
  { label: '桂林银行', value: 'GLYH' },
  { label: '微信支付', value: 'WECHAT' }
];

interface FilterOverride {
  keyword?: string;
  bankCode?: string;
}

const StatementsPage: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [bankCode, setBankCode] = useState('');
  const [list, setList] = useState<Statement[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const pageRef = useRef(1);
  const finishedRef = useRef(false);
  const loadingRef = useRef(false);
  const customerTags = useRiskTags('CUSTOMER');
  const profile = useAuthStore((state) => state.profile);
  const permissions = profile?.permissions || [];
  const canUpload =
    permissions.includes('*') || permissions.includes('bankStatement:upload');

  const fetchList = useCallback(
    async (nextPage: number, reset = false, override: FilterOverride = {}) => {
      if (loadingRef.current) return;
      loadingRef.current = true;
      setLoading(true);

      const activeKeyword = override.keyword !== undefined ? override.keyword : keyword;
      const activeBank = override.bankCode !== undefined ? override.bankCode : bankCode;

      try {
        const result = await getStatements({
          keyword: activeKeyword.trim() || undefined,
          bankCode: activeBank || undefined,
          page: nextPage,
          size: PAGE_SIZE
        });
        console.info('[Statements] 列表加载完成', {
          page: nextPage,
          size: result.list.length,
          total: result.total
        });
        setList((prev) => (reset ? result.list : [...prev, ...result.list]));
        setTotal(result.total);
        customerTags.load(result.list.map((item) => item.accountHolder));
        pageRef.current = nextPage;
        finishedRef.current = result.list.length < PAGE_SIZE;
      } catch (err) {
        console.error('[Statements] 列表加载失败', err);
        Taro.showToast({ title: '加载失败，请重试', icon: 'none' });
      } finally {
        loadingRef.current = false;
        setLoading(false);
      }
    },
    [bankCode, keyword]
  );

  useEffect(() => {
    fetchList(1, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSearch = () => {
    fetchList(1, true, { keyword });
  };

  const handleBankChange = (value: string) => {
    setBankCode(value);
    fetchList(1, true, { bankCode: value });
  };

  const showImportResult = (result: StatementImportResult) => {
    if (result.duplicated) {
      Taro.showModal({
        title: '无需重复上传',
        content: '该账期的流水已存在，未重复入库。',
        showCancel: false
      });
      return;
    }
    const bank = `${result.bankName || ''} ${result.accountHolder || ''}`.trim();
    const lines = [
      `银行/户名：${bank || '--'}`,
      `账期：${result.startDate || '--'} ~ ${result.endDate || '--'}`,
      `明细：${result.transactionCount} 笔`,
      result.merged ? '账期重叠，已与同账号流水合并去重' : ''
    ].filter(Boolean);

    if (result.warnings && result.warnings.length > 0) {
      const shown = result.warnings.slice(0, 5).join('\n');
      const more =
        result.warnings.length > 5 ? `\n…共 ${result.warnings.length} 条提示` : '';
      Taro.showModal({
        title: '已入库，请核对校验提示',
        content: `${lines.join('\n')}\n\n${shown}${more}`,
        showCancel: false,
        confirmText: '知道了'
      });
      return;
    }
    Taro.showModal({ title: '上传成功', content: lines.join('\n'), showCancel: false });
  };

  /** 上传流水文件（PDF / 图片）：从微信聊天记录选择文件 → 后端解析入库 → 刷新列表 */
  const handleUpload = async () => {
    if (uploading) return;
    if (process.env.TARO_ENV !== 'weapp') {
      Taro.showToast({ title: '请在微信中使用上传功能', icon: 'none' });
      return;
    }
    let file: { name: string; path: string; size: number } | undefined;
    try {
      const picked = await Taro.chooseMessageFile({
        count: 1,
        type: 'file',
        extension: ['pdf', 'jpg', 'jpeg', 'png', 'bmp', 'tif', 'tiff']
      });
      file = picked.tempFiles?.[0];
    } catch (err) {
      // 用户取消选择
      return;
    }
    if (!file) return;
    if (!/\.(pdf|jpe?g|png|bmp|tiff?)$/i.test(file.name)) {
      Taro.showToast({ title: '仅支持 PDF 或图片文件', icon: 'none' });
      return;
    }
    if (file.size > 50 * 1024 * 1024) {
      Taro.showToast({ title: '文件不能超过 50MB', icon: 'none' });
      return;
    }

    setUploading(true);
    Taro.showLoading({ title: '正在解析入库...', mask: true });
    try {
      const result = await uploadStatementPdf(file.path, file.name);
      Taro.hideLoading();
      showImportResult(result);
      await fetchList(1, true);
    } catch (err) {
      Taro.hideLoading();
      Taro.showModal({
        title: '上传失败',
        content: (err as Error).message || '请稍后重试',
        showCancel: false
      });
    } finally {
      setUploading(false);
    }
  };

  usePullDownRefresh(async () => {
    await fetchList(1, true);
    Taro.stopPullDownRefresh();
  });

  useReachBottom(() => {
    if (finishedRef.current || loadingRef.current) return;
    fetchList(pageRef.current + 1);
  });

  const openDetail = (statement: Statement) => {
    Taro.navigateTo({ url: `/pages/statement-detail/index?id=${statement._id}` });
  };

  return (
    <View className={styles.page}>
      <View className={styles.toolbar}>
        <View className={styles.searchBox}>
          <Text className={styles.searchIcon}>⌕</Text>
          <Input
            className={styles.searchInput}
            value={keyword}
            placeholder="搜索户名或账号"
            placeholderClass={styles.placeholder}
            confirmType="search"
            onInput={(event) => setKeyword(event.detail.value)}
            onConfirm={handleSearch}
          />
        </View>
        <View className={styles.searchBtn} onClick={handleSearch}>
          <Text className={styles.searchBtnText}>搜索</Text>
        </View>
        {canUpload ? (
          <View
            className={classnames(styles.uploadBtn, uploading && styles.uploadBtnDisabled)}
            onClick={handleUpload}
          >
            <Text className={styles.uploadBtnText}>{uploading ? '上传中' : '上传'}</Text>
          </View>
        ) : null}
      </View>

      <View className={styles.filterRow}>
        {BANK_FILTERS.map((item) => (
          <View
            key={item.value || 'all'}
            className={classnames(
              styles.filterItem,
              bankCode === item.value && styles.filterItemActive
            )}
            onClick={() => handleBankChange(item.value)}
          >
            <Text
              className={classnames(
                styles.filterText,
                bankCode === item.value && styles.filterTextActive
              )}
            >
              {item.label}
            </Text>
          </View>
        ))}
      </View>

      <View className={styles.summary}>
        <Text className={styles.summaryText}>共 {total} 份流水单</Text>
      </View>

      <View className={styles.list}>
        {list.map((item) => (
          <StatementCard
            key={item._id}
            statement={item}
            tags={customerTags.tagsFor(item.accountHolder)}
            onClick={openDetail}
          />
        ))}
      </View>

      {!loading && list.length === 0 ? <EmptyState text="没有找到匹配的流水单" /> : null}
      {loading ? <Text className={styles.loadingText}>加载中...</Text> : null}
      {!loading && list.length > 0 && finishedRef.current ? (
        <Text className={styles.loadingText}>没有更多了</Text>
      ) : null}
    </View>
  );
};

export default StatementsPage;
