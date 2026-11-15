const cloud = require('wx-server-sdk');

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = cloud.database();

const formatTime = (value) => {
  if (!value) return '';
  const date = value instanceof Date ? value : new Date(value);
  const pad = (num) => String(num).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours()
  )}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};

exports.main = async () => {
  try {
    const { OPENID } = cloud.getWXContext();
    const users = db.collection('users');
    const found = await users.where({ ownerOpenid: OPENID }).limit(1).get();

    if (found.data.length > 0) {
      const user = found.data[0];
      await users.doc(user._id).update({ data: { lastLoginTime: db.serverDate() } });
      return {
        code: 0,
        message: 'success',
        data: {
          user: {
            openid: OPENID,
            nickname: user.nickname || '微信用户',
            avatar: user.avatar || '',
            lastLoginTime: formatTime(new Date())
          }
        }
      };
    }

    const profile = {
      ownerOpenid: OPENID,
      nickname: '微信用户',
      avatar: '',
      createTime: db.serverDate(),
      lastLoginTime: db.serverDate()
    };
    await users.add({ data: profile });
    return {
      code: 0,
      message: 'success',
      data: {
        user: {
          openid: OPENID,
          nickname: profile.nickname,
          avatar: profile.avatar,
          lastLoginTime: formatTime(new Date())
        }
      }
    };
  } catch (err) {
    console.error('[login] error:', err);
    return { code: -1, message: err.message || '登录失败', data: null };
  }
};
