-- 征信报告明细表：实体见 CreditReportItem（混合存放信用卡/贷款/担保/查询记录，用 item_type 区分）
-- 仅补建缺失的表，已存在时跳过，不修改任何现有表和数据。
-- 背景：该表的实体 @Comment 曾包含英文单引号，Hibernate 生成的建表 DDL 语法错误导致表从未创建成功。
-- noinspection SqlNoDataSourceInspection

CREATE TABLE IF NOT EXISTS credit_report_item (
  id bigint NOT NULL AUTO_INCREMENT,
  account_no varchar(64) DEFAULT NULL COMMENT '账号（信用卡/贷款，如有）',
  amount decimal(18,2) DEFAULT NULL COMMENT '金额：信用卡=信用额度 贷款=发放金额 担保=相关还款责任金额',
  balance decimal(18,2) DEFAULT NULL COMMENT '当前余额（贷款/担保使用）',
  contract_no varchar(512) DEFAULT NULL COMMENT '保证合同编号（仅 GUARANTEE 使用）',
  currency varchar(16) DEFAULT NULL COMMENT '币种：人民币/美元',
  expiry_date date DEFAULT NULL COMMENT '到期日期（贷款：到期日；信用卡销户：销户月份；担保：额度有效期）',
  guarantee_company_id_no varchar(512) DEFAULT NULL COMMENT '被担保企业的证件号码/中征码（仅 GUARANTEE 使用）',
  guarantee_company_id_type varchar(64) DEFAULT NULL COMMENT '被担保企业的证件类型：中征码/统一社会信用代码（仅 GUARANTEE 使用）',
  guarantee_company_name varchar(512) DEFAULT NULL COMMENT '被担保企业名称（仅 GUARANTEE 使用）',
  item_no int DEFAULT NULL COMMENT '报告内序号（1 起，对应 PDF 中的序号 1、2…）',
  item_type varchar(32) DEFAULT NULL COMMENT '条目类型：CREDIT_CARD=信用卡 LOAN=贷款 GUARANTEE=担保 ORG_QUERY=机构查询 PERSON_QUERY=个人查询',
  open_date date DEFAULT NULL COMMENT '发放/授信/担保日期（信用卡、贷款、担保使用）',
  org_name varchar(256) DEFAULT NULL COMMENT '机构/银行名称（放款机构、发卡行、担保权人、查询机构）',
  product_type varchar(128) DEFAULT NULL COMMENT '产品类型：贷记卡/个人经营性贷款/个人住房商业贷款/其他个人消费贷款等',
  query_date date DEFAULT NULL COMMENT '查询日期（仅 ORG_QUERY / PERSON_QUERY 使用）',
  query_reason varchar(128) DEFAULT NULL COMMENT '查询原因：贷后管理/贷款审批/担保资格审查/信用卡审批/本人查询等（仅 QUERY 使用）',
  query_subject varchar(64) DEFAULT NULL COMMENT '查询主体：本人/机构（仅 QUERY 使用）',
  raw_sentence varchar(2000) DEFAULT NULL COMMENT 'PDF 中该条目的原始完整句子，用于追溯核对',
  responsibility_type varchar(16) DEFAULT NULL COMMENT '责任人类型：保证人/抵押人等（仅 GUARANTEE 使用）',
  status varchar(64) DEFAULT NULL COMMENT '账户状态：未结清/已结清/已销户/可循环使用/当前无逾期等',
  tail_no varchar(32) DEFAULT NULL COMMENT '卡片尾号（仅 CREDIT_CARD 使用）',
  used_amount decimal(18,2) DEFAULT NULL COMMENT '已使用额度（仅 CREDIT_CARD 使用）',
  credit_report_id bigint NOT NULL COMMENT '所属征信报告（credit_report.id）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_credit_report_item (credit_report_id, item_type, item_no, org_name),
  KEY idx_cri_report_id (credit_report_id),
  KEY idx_cri_type (item_type),
  KEY idx_cri_org (org_name),
  CONSTRAINT FKcbqkxdn0i01n4uqd2x0yu6wkr FOREIGN KEY (credit_report_id) REFERENCES credit_report (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='征信报告明细：混合存放信用卡/贷款/担保/查询记录，用 item_type 区分，各类型只填自己的字段';

-- 网贷平台关键词库：手工维护平台名称与匹配关键词（仅补建缺失表，不改动现有表和数据）
CREATE TABLE IF NOT EXISTS lending_platform (
  id bigint NOT NULL AUTO_INCREMENT,
  name varchar(128) NOT NULL COMMENT '平台/产品名称，如 借呗、微粒贷',
  keywords varchar(512) NOT NULL COMMENT '匹配关键词，多个用英文逗号分隔',
  enabled bit(1) NOT NULL DEFAULT b'1' COMMENT '是否启用：停用后不参与匹配',
  builtin bit(1) NOT NULL DEFAULT b'0' COMMENT '是否系统内置兜底平台：不可删除，仅在正式平台都未命中时参与匹配',
  remark varchar(255) DEFAULT NULL COMMENT '备注',
  create_time datetime(6) DEFAULT NULL COMMENT '创建时间',
  update_time datetime(6) DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lending_platform_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='网贷平台关键词库：平台名称加匹配关键词，可手工维护';

-- 网贷命中记录：派生数据，可整表重建；一笔流水最多一条，避免重复统计
CREATE TABLE IF NOT EXISTS lending_transaction_match (
  id bigint NOT NULL AUTO_INCREMENT,
  transaction_id bigint NOT NULL COMMENT '银行流水明细 id',
  statement_id bigint NOT NULL COMMENT '所属流水单 id',
  account_holder varchar(128) DEFAULT NULL COMMENT '账户户名快照，作为客户归属维度',
  bank_name varchar(128) DEFAULT NULL COMMENT '银行名称快照',
  account_no varchar(64) DEFAULT NULL COMMENT '账号快照',
  trans_date date DEFAULT NULL COMMENT '交易日期快照',
  trans_month varchar(7) DEFAULT NULL COMMENT '交易月份 yyyy-MM',
  amount decimal(18,2) DEFAULT NULL COMMENT '交易金额快照，正数=收入 负数=支出',
  direction varchar(8) DEFAULT NULL COMMENT '借贷标志：入=收入 出=支出 其他=不计收支',
  counterparty_name varchar(128) DEFAULT NULL COMMENT '对方户名快照',
  summary varchar(500) DEFAULT NULL COMMENT '摘要快照',
  platform_id bigint NOT NULL COMMENT '命中的平台 id',
  platform_name varchar(128) NOT NULL COMMENT '命中的平台名称快照',
  keyword varchar(64) NOT NULL COMMENT '命中的关键词',
  matched_field varchar(32) NOT NULL COMMENT '命中字段：COUNTERPARTY_NAME=对方户名 SUMMARY=摘要',
  create_time datetime(6) DEFAULT NULL COMMENT '匹配时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_lending_match_tx (transaction_id),
  KEY idx_lending_match_month (trans_month),
  KEY idx_lending_match_platform (platform_id),
  KEY idx_lending_match_direction (direction),
  KEY idx_lending_match_statement (statement_id),
  KEY idx_lending_match_holder (account_holder)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='网贷命中记录：一笔流水最多一条，保存命中的平台、关键词与流水快照，可整表重建';
