import { mockStatements, mockTransactions } from './mockBase';
import type {
  CounterpartyAccountOption,
  CounterpartyAnalysis,
  CounterpartyRow
} from '@/types';

const normalize = (value?: string | null): string =>
  (value || '').replace(/\s+/g, '').toLowerCase();

interface CpAgg {
  displayName: string;
  incomeCount: number;
  incomeTotal: number;
  expenseCount: number;
  expenseTotal: number;
  lastDate: string | null;
  accountNos: Set<string>;
  holders: Set<string>;
  txnCount: number;
  selfCount: number;
  selfAmount: number;
}

/**
 * H5 预览用的往来对手 mock：口径与后端 /bank-statements/counterparties 一致
 * （按对方户名归并，只返回同时存在转入与转出的对手，按往来总额降序）
 */
const getCounterpartyAnalysis = (
  params: { accountHolder?: string } = {}
): CounterpartyAnalysis => {
  const holderFilter = normalize(params.accountHolder);
  const statements = mockStatements.filter(
    (item) => !holderFilter || normalize(item.accountHolder) === holderFilter
  );
  const statementIds = new Set(statements.map((item) => item._id));
  const accountOwner = new Map(statements.map((item) => [item.accountNo, item.accountHolder]));
  const transactions = mockTransactions.filter((item) => statementIds.has(item.statementId));

  const groups = new Map<string, CpAgg>();
  let totalIncome = 0;
  let totalExpense = 0;
  let selfTransferCount = 0;
  let selfTransferAmount = 0;
  let excludedBlank = 0;

  transactions.forEach((txn) => {
    const displayName = (txn.counterpartyName || '').trim();
    if (!displayName) {
      excludedBlank += 1;
      return;
    }
    const key = normalize(displayName);
    const agg =
      groups.get(key) ||
      ({
        displayName,
        incomeCount: 0,
        incomeTotal: 0,
        expenseCount: 0,
        expenseTotal: 0,
        lastDate: null,
        accountNos: new Set<string>(),
        holders: new Set<string>(),
        txnCount: 0,
        selfCount: 0,
        selfAmount: 0
      } as CpAgg);

    const amount = Math.abs(Number(txn.amount) || 0);
    const isIncome = (txn.direction || (Number(txn.amount) >= 0 ? '入' : '出')) === '入';
    if (isIncome) {
      agg.incomeCount += 1;
      agg.incomeTotal += amount;
      totalIncome += amount;
    } else {
      agg.expenseCount += 1;
      agg.expenseTotal += amount;
      totalExpense += amount;
    }
    agg.txnCount += 1;
    if (txn.transDate && (!agg.lastDate || txn.transDate > agg.lastDate)) {
      agg.lastDate = txn.transDate;
    }
    if (txn.counterpartyAccount) {
      agg.accountNos.add(txn.counterpartyAccount);
    }
    const statement = statements.find((item) => item._id === txn.statementId);
    if (statement?.accountHolder) {
      agg.holders.add(statement.accountHolder);
    }
    const self =
      key === normalize(statement?.accountHolder) ||
      (Boolean(txn.counterpartyAccount) && accountOwner.has(txn.counterpartyAccount!));
    if (self) {
      agg.selfCount += 1;
      agg.selfAmount += amount;
      selfTransferCount += 1;
      selfTransferAmount += amount;
    }
    groups.set(key, agg);
  });

  const items: CounterpartyRow[] = Array.from(groups.entries())
    .filter(([, agg]) => agg.incomeCount > 0 && agg.expenseCount > 0)
    .map(([name, agg]) => ({
      name,
      displayName: agg.displayName,
      incomeCount: agg.incomeCount,
      incomeTotal: agg.incomeTotal,
      expenseCount: agg.expenseCount,
      expenseTotal: agg.expenseTotal,
      netAmount: agg.incomeTotal - agg.expenseTotal,
      totalFlow: agg.incomeTotal + agg.expenseTotal,
      lastDate: agg.lastDate,
      accountCount: agg.accountNos.size,
      holderCount: agg.holders.size,
      transactionCount: agg.txnCount,
      selfTransferCount: agg.selfCount,
      selfTransferAmount: agg.selfAmount
    }))
    .sort((a, b) => b.totalFlow - a.totalFlow || a.name.localeCompare(b.name));

  const accounts: CounterpartyAccountOption[] = statements
    .filter((item) => Boolean(item.accountNo))
    .map((item) => ({
      accountNo: item.accountNo,
      bankName: item.bankName,
      accountHolder: item.accountHolder
    }));

  return {
    counterpartyCount: groups.size,
    dualCount: items.length,
    transactionCount: transactions.length,
    excludedBlankCount: excludedBlank,
    totalIncome,
    totalExpense,
    selfTransferCount,
    selfTransferAmount,
    holders: Array.from(new Set(statements.map((item) => item.accountHolder).filter(Boolean))),
    accounts,
    items
  };
};

export default getCounterpartyAnalysis;
