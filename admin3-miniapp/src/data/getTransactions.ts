import type { PageResult, Transaction, TransactionQuery } from '@/types';
import { mockTransactions } from './mockBase';

export default function mockGetTransactions(params: TransactionQuery): PageResult<Transaction> {
  const page = params?.page && params.page > 0 ? params.page : 1;
  const size = params?.size && params.size > 0 ? params.size : 20;
  const keyword = (params?.keyword || '').trim();

  let list = mockTransactions.filter((item) => item.statementId === params?.statementId);

  if (params?.direction) {
    list = list.filter((item) => item.direction === params.direction);
  }
  if (keyword) {
    list = list.filter(
      (item) =>
        (item.counterpartyName || '').includes(keyword) ||
        (item.summary || '').includes(keyword) ||
        (item.remark || '').includes(keyword)
    );
  }

  const start = (page - 1) * size;
  return {
    list: list.slice(start, start + size),
    total: list.length
  };
}
