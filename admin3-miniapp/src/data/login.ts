import type { UserProfile } from '@/types';

interface LoginResult {
  user: UserProfile;
}

export default function mockLogin(): LoginResult {
  const user: UserProfile = {
    openid: 'mock-openid-0001',
    nickname: '张伟',
    avatar: 'https://picsum.photos/id/64/200/200',
    lastLoginTime: '2026-11-12 09:20:31'
  };
  return { user };
}
