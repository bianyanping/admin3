import type { PageResult, Statement, StatementQuery } from '@/types';
import { mockStatements } from './mockBase';

export default function mockGetStatements(params?: StatementQuery): PageResult<Statement> {
  const page = params?.page && params.page > 0 ? params.page : 1;
  const size = params?.size && params.size > 0 ? params.size : 10;
  const keyword = (params?.keyword || '').trim();

  let list = mockStatements;
  if (keyword) {
    list = list.filter(
      (item) => item.accountHolder.includes(keyword) || item.accountNo.includes(keyword)
    );
  }
  if (params?.bankCode) {
    list = list.filter((item) => item.bankCode === params.bankCode);
  }

  const start = (page - 1) * size;
  return {
    list: list.slice(start, start + size),
    total: list.length
  };
}
