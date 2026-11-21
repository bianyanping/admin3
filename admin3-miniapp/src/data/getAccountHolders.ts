import { mockStatements } from './mockBase';
import type { AccountHolderOption } from '@/types';

/**
 * H5 预览用的户名列表 mock：按流水单聚合户名、流水单数、账户数
 */
const getAccountHolders = (): AccountHolderOption[] => {
  const map = new Map<string, { statements: number; accounts: Set<string> }>();
  mockStatements.forEach((item) => {
    const holder = (item.accountHolder || '').trim();
    if (!holder) return;
    const agg = map.get(holder) || { statements: 0, accounts: new Set<string>() };
    agg.statements += 1;
    agg.accounts.add(item.accountNo);
    map.set(holder, agg);
  });
  return Array.from(map.entries())
    .sort((a, b) => a[0].localeCompare(b[0], 'zh-CN'))
    .map(([accountHolder, agg]) => ({
      accountHolder,
      statementCount: agg.statements,
      accountCount: agg.accounts.size
    }));
};

export default getAccountHolders;
