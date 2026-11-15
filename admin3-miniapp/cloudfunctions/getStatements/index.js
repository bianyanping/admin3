const cloud = require('wx-server-sdk');

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = cloud.database();

const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

exports.main = async (event) => {
  try {
    const { OPENID } = cloud.getWXContext();
    const page = Math.max(Number(event.page) || 1, 1);
    const size = Math.min(Math.max(Number(event.size) || 10, 1), 50);
    const keyword = (event.keyword || '').trim();

    const where = { ownerOpenid: OPENID };
    if (event.bankCode) {
      where.bankCode = event.bankCode;
    }
    if (keyword) {
      const reg = db.RegExp({ regexp: escapeRegExp(keyword), options: 'i' });
      where.$or = [{ accountHolder: reg }, { accountNo: reg }];
    }

    const collection = db.collection('statements');
    const countResult = await collection.where(where).count();
    const listResult = await collection
      .where(where)
      .orderBy('startDate', 'desc')
      .skip((page - 1) * size)
      .limit(size)
      .get();

    return {
      code: 0,
      message: 'success',
      data: {
        list: listResult.data,
        total: countResult.total
      }
    };
  } catch (err) {
    console.error('[getStatements] error:', err);
    return { code: -1, message: err.message || '查询失败', data: null };
  }
};
