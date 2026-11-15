const cloud = require('wx-server-sdk');

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = cloud.database();

const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

exports.main = async (event) => {
  try {
    const { OPENID } = cloud.getWXContext();
    const statementId = event.statementId;
    if (!statementId) {
      return { code: -1, message: '缺少流水单 id', data: null };
    }

    const page = Math.max(Number(event.page) || 1, 1);
    const size = Math.min(Math.max(Number(event.size) || 20, 1), 100);
    const keyword = (event.keyword || '').trim();

    const where = { ownerOpenid: OPENID, statementId };
    if (event.direction) {
      where.direction = event.direction;
    }
    if (keyword) {
      const reg = db.RegExp({ regexp: escapeRegExp(keyword), options: 'i' });
      where.$or = [{ counterpartyName: reg }, { summary: reg }, { remark: reg }];
    }

    const collection = db.collection('transactions');
    const countResult = await collection.where(where).count();
    const listResult = await collection
      .where(where)
      .orderBy('transDate', 'desc')
      .orderBy('seqNo', 'desc')
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
    console.error('[getTransactions] error:', err);
    return { code: -1, message: err.message || '查询失败', data: null };
  }
};
