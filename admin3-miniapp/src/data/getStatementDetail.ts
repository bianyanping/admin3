import type { Statement } from '@/types';
import { mockStatements } from './mockBase';

interface DetailParams {
  id: string;
}

export default function mockGetStatementDetail(params?: DetailParams): Statement {
  const found = mockStatements.find((item) => item._id === params?.id);
  return found || mockStatements[0];
}
