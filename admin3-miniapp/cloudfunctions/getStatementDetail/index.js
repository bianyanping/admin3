const cloud = require('wx-server-sdk');

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = cloud.database();

exports.main = async (event) => {
  try {
    const { OPENID } = cloud.getWXContext();
    const id = event.id;
    if (!id) {
      return { code: -1, message: '缺少流水单 id', data: null };
    }

    const result = await db
      .collection('statements')
      .where({ _id: id, ownerOpenid: OPENID })
      .limit(1)
      .get();

    if (result.data.length === 0) {
      return { code: -1, message: '流水单不存在或无权访问', data: null };
    }

    return { code: 0, message: 'success', data: result.data[0] };
  } catch (err) {
    console.error('[getStatementDetail] error:', err);
    return { code: -1, message: err.message || '查询失败', data: null };
  }
};
