/**
 * 金额 / 日期格式化工具
 */

/** 千分位金额，保留两位小数 */
export const formatMoney = (value?: number | null): string => {
  const num = Number(value ?? 0);
  return num.toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  });
};

/** 带正负号的金额，用于明细 */
export const formatSignedMoney = (value?: number | null): string => {
  const num = Number(value ?? 0);
  const sign = num > 0 ? '+' : num < 0 ? '-' : '';
  return `${sign}${formatMoney(Math.abs(num))}`;
};

/** 大额数字压缩为「万 / 亿」，用于指标卡 */
export const formatShortMoney = (value?: number | null): string => {
  const num = Math.abs(Number(value ?? 0));
  if (num >= 100000000) return `${(num / 100000000).toFixed(2)}亿`;
  if (num >= 10000) return `${(num / 10000).toFixed(1)}万`;
  return num.toLocaleString('zh-CN', { maximumFractionDigits: 0 });
};

/** YYYY-MM-DD → MM-DD */
export const formatDayLabel = (date?: string | null): string => {
  if (!date) return '';
  return date.length >= 10 ? date.slice(5, 10) : date;
};

/** 账号脱敏：保留前 4 位与后 4 位 */
export const maskAccount = (accountNo?: string | null): string => {
  if (!accountNo) return '--';
  const value = accountNo.trim();
  if (value.length <= 8) return value;
  return `${value.slice(0, 4)} **** ${value.slice(-4)}`;
};
