const cloud = require('wx-server-sdk');

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = cloud.database();
const _ = db.command;

const ANOMALY_THRESHOLD = 50000;

const pad = (num) => String(num).padStart(2, '0');

const formatDate = (date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;

/** 分批拉取集合数据（云数据库单次最多 100 条） */
const fetchAll = async (collectionName, where, max = 2000) => {
  const collection = db.collection(collectionName);
  const result = [];
  let skip = 0;
  while (skip < max) {
    const res = await collection.where(where).skip(skip).limit(100).get();
    result.push(...res.data);
    if (res.data.length < 100) break;
    skip += 100;
  }
  return result;
};

/** 按摘要关键字归类 */
const classifySummary = (summary) => {
  if (!summary) return '其他';
  if (summary.includes('工资') || summary.includes('薪')) return '工资';
  if (summary.includes('利息')) return '利息';
  if (summary.includes('手续费') || summary.includes('服务费') || summary.includes('费用')) {
    return '手续费';
  }
  if (
    summary.includes('转账') ||
    summary.includes('汇款') ||
    summary.includes('转出') ||
    summary.includes('转入') ||
    summary.includes('跨行') ||
    summary.includes('汇入') ||
    summary.includes('汇出')
  ) {
    return '转账';
  }
  if (
    summary.includes('消费') ||
    summary.includes('购物') ||
    summary.includes('支付') ||
    summary.includes('刷卡') ||
    summary.includes('快捷')
  ) {
    return '消费';
  }
  return '其他';
};

exports.main = async (event) => {
  try {
    const { OPENID } = cloud.getWXContext();
    const days = Math.min(Math.max(Number(event.days) || 30, 1), 365);

    const to = new Date();
    const from = new Date();
    from.setDate(from.getDate() - (days - 1));
    const fromStr = formatDate(from);
    const toStr = formatDate(to);

    const rows = await fetchAll(
      'transactions',
      {
        ownerOpenid: OPENID,
        transDate: _.gte(fromStr).and(_.lte(toStr))
      },
      2000
    );

    const dailyMap = {};
    const categoryMap = {};
    const counterpartyMap = {};
    const largeList = [];

    let totalIncome = 0;
    let totalExpense = 0;
    let todayCount = 0;
    let largeCount = 0;

    for (const item of rows) {
      const amount = Number(item.amount) || 0;
      if (amount >= 0) {
        totalIncome += amount;
      } else {
        totalExpense += Math.abs(amount);
      }
      if (item.transDate === toStr) {
        todayCount += 1;
      }
      if (Math.abs(amount) >= ANOMALY_THRESHOLD) {
        largeCount += 1;
        largeList.push(item);
      }

      if (item.transDate) {
        if (!dailyMap[item.transDate]) {
          dailyMap[item.transDate] = { income: 0, expense: 0 };
        }
        if (amount >= 0) {
          dailyMap[item.transDate].income += amount;
        } else {
          dailyMap[item.transDate].expense += Math.abs(amount);
        }
      }

      const category = classifySummary(item.summary);
      categoryMap[category] = (categoryMap[category] || 0) + Math.abs(amount);

      if (item.counterpartyName) {
        counterpartyMap[item.counterpartyName] =
          (counterpartyMap[item.counterpartyName] || 0) + Math.abs(amount);
      }
    }

    const daily = [];
    for (let i = 0; i < days; i++) {
      const date = new Date(from);
      date.setDate(from.getDate() + i);
      const key = formatDate(date);
      const bucket = dailyMap[key] || { income: 0, expense: 0 };
      daily.push({ date: key, income: bucket.income, expense: bucket.expense });
    }

    const categorySum = Object.values(categoryMap).reduce((acc, cur) => acc + cur, 0);
    const categories = Object.keys(categoryMap)
      .map((name) => ({
        name,
        amount: categoryMap[name],
        percent: categorySum > 0 ? (categoryMap[name] * 100) / categorySum : 0
      }))
      .sort((a, b) => b.amount - a.amount)
      .slice(0, 6);

    const topCounterparties = Object.keys(counterpartyMap)
      .map((name) => ({ name, amount: counterpartyMap[name] }))
      .sort((a, b) => b.amount - a.amount)
      .slice(0, 10);

    const recentLarge = largeList
      .sort((a, b) => (a.transDate < b.transDate ? 1 : -1))
      .slice(0, 10)
      .map((item) => ({
        _id: item._id,
        statementId: item.statementId,
        transDate: item.transDate,
        amount: item.amount,
        counterpartyName: item.counterpartyName,
        summary: item.summary
      }));

    return {
      code: 0,
      message: 'success',
      data: {
        totalCount: rows.length,
        totalIncome,
        totalExpense,
        todayCount,
        largeCount,
        daily,
        categories,
        topCounterparties,
        recentLarge
      }
    };
  } catch (err) {
    console.error('[getDashboard] error:', err);
    return { code: -1, message: err.message || '统计失败', data: null };
  }
};
