-- =============================================================================
-- 组织层级数据隔离同步脚本（兼容列名/索引名差异版本）
-- -----------------------------------------------------------------------------
-- 目标数据库类型：MySQL 8.0+，InnoDB / utf8mb4
-- 适用模块：风险三库（资方 / 黑名单 / 交易对手）、网贷分析、网贷关键词库
--
-- 本版本解决的问题：
--   1. 不同环境 Hibernate 物理命名策略可能不一致，例如
--      transactionId 与 transaction_id、create_time 与 created_time。
--   2. 旧索引名称可能不同，甚至不存在。
--   3. 某些环境可能缺少个别表或列。
--
-- 处理原则：
--   - 所有表是否存在、列是否存在、索引是否存在，均先查 information_schema，
--     再决定是否拼 DDL/DML；缺失时输出明确提示，不直接报错中断。
--   - 不再手写固定的 camelCase 列名；交易流水列会自动识别
--     transaction_id / transactionId 两种命名。
--   - 脚本可重复执行。
--
-- 执行前备份（示例）：
--   mysqldump -u<user> -p <db> risk_counterparty risk_tag_link \
--     lending_platform lending_transaction_match > backup_org_scope.sql
-- =============================================================================

SET NAMES utf8mb4;
SET @db := DATABASE();

-- =============================================================================
-- 0. 预检：表是否存在、关键列实际名称
-- =============================================================================

SET @cp_tbl := (SELECT table_name FROM information_schema.tables
  WHERE table_schema = @db AND LOWER(table_name) = 'risk_counterparty' LIMIT 1);
SET @rtl_tbl := (SELECT table_name FROM information_schema.tables
  WHERE table_schema = @db AND LOWER(table_name) = 'risk_tag_link' LIMIT 1);
SET @lp_tbl := (SELECT table_name FROM information_schema.tables
  WHERE table_schema = @db AND LOWER(table_name) = 'lending_platform' LIMIT 1);
SET @ltm_tbl := (SELECT table_name FROM information_schema.tables
  WHERE table_schema = @db AND LOWER(table_name) = 'lending_transaction_match' LIMIT 1);
SET @org_tbl := (SELECT table_name FROM information_schema.tables
  WHERE table_schema = @db AND LOWER(table_name) = 'organization' LIMIT 1);
SET @user_tbl := (SELECT table_name FROM information_schema.tables
  WHERE table_schema = @db AND LOWER(table_name) = 'user' LIMIT 1);

-- 网贷命中表的交易流水列：兼容 transaction_id / transactionId
SET @ltm_tx := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @ltm_tbl
    AND LOWER(column_name) IN ('transaction_id', 'transactionid')
  ORDER BY (LOWER(column_name) = 'transaction_id') DESC LIMIT 1);
SET @ltm_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @ltm_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);
SET @ltm_id := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @ltm_tbl
    AND LOWER(column_name) = 'id' LIMIT 1);

SET @cp_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @cp_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);
SET @cp_name_key := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @cp_tbl
    AND LOWER(column_name) = 'name_key' LIMIT 1);
SET @cp_owner := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @cp_tbl
    AND LOWER(column_name) = 'owner_user_id' LIMIT 1);

SET @rtl_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);
SET @rtl_owner := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'owner_user_id' LIMIT 1);
SET @rtl_source := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'source' LIMIT 1);
SET @rtl_subject_type := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'subject_type' LIMIT 1);
SET @rtl_subject_key := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'subject_key' LIMIT 1);
SET @rtl_tag_id := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'tag_id' LIMIT 1);
SET @rtl_id := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'id' LIMIT 1);

SET @lp_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);
SET @lp_name := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) = 'name' LIMIT 1);
SET @lp_keywords := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) = 'keywords' LIMIT 1);
SET @lp_enabled := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) = 'enabled' LIMIT 1);
SET @lp_builtin := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) = 'builtin' LIMIT 1);
SET @lp_remark := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) = 'remark' LIMIT 1);
SET @lp_create := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) IN ('create_time', 'created_time')
  ORDER BY (LOWER(column_name) = 'create_time') DESC LIMIT 1);
SET @lp_update := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) IN ('update_time', 'updated_time', 'last_modified_time')
  ORDER BY (LOWER(column_name) = 'update_time') DESC LIMIT 1);

SET @org_id_col := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @org_tbl
    AND LOWER(column_name) = 'id' LIMIT 1);
SET @user_id_col := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @user_tbl
    AND LOWER(column_name) = 'id' LIMIT 1);
SET @user_org_col := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @user_tbl
    AND LOWER(column_name) = 'organization_id' LIMIT 1);

SELECT 'preflight' AS section, expected_table,
  IF(actual_table IS NULL, 'MISSING', 'OK') AS table_status,
  actual_table AS actual_table_name
FROM (
  SELECT 'risk_counterparty' AS expected_table, @cp_tbl AS actual_table
  UNION ALL SELECT 'risk_tag_link', @rtl_tbl
  UNION ALL SELECT 'lending_platform', @lp_tbl
  UNION ALL SELECT 'lending_transaction_match', @ltm_tbl
  UNION ALL SELECT 'organization', @org_tbl
  UNION ALL SELECT 'user', @user_tbl
) t;

SELECT 'transaction_column_check' AS section,
  IF(@ltm_tbl IS NULL, 'lending_transaction_match 表不存在',
     IF(@ltm_tx IS NULL,
        '未找到 transaction_id / transactionId 列，将跳过唯一索引创建',
        CONCAT('实际交易流水列：', @ltm_tx))) AS message;

-- =============================================================================
-- 1. 交易对手库 risk_counterparty
-- =============================================================================

-- 1.1 增加 org_id
SET @sql := IF(@cp_tbl IS NULL,
  'SELECT ''提示：risk_counterparty 表不存在，跳过 org_id 字段'' AS message',
  IF(@cp_org IS NOT NULL,
     'SELECT ''提示：risk_counterparty.org_id 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @cp_tbl, '` ADD COLUMN `org_id` bigint DEFAULT NULL COMMENT ''数据归属组织 id''')
  ));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 重新读取 org_id 实际列名
SET @cp_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @cp_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);

-- 1.2 派生数据清空，等待各组织首次访问时按组织子树重建
SET @sql := IF(@cp_tbl IS NULL,
  'SELECT ''提示：risk_counterparty 表不存在，跳过数据清理'' AS message',
  CONCAT('DELETE FROM `', @cp_tbl, '`'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.3 删除任意旧唯一索引（按列组合识别，兼容索引名差异）：
--     旧口径为 (owner_user_id, name_key)，顺序不限
SET @drop := (SELECT CONCAT('ALTER TABLE `', @cp_tbl, '` ',
    GROUP_CONCAT(CONCAT('DROP INDEX `', index_name, '`') SEPARATOR ', '))
  FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @cp_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 2
       AND SUM(LOWER(column_name) = 'owner_user_id') = 1
       AND SUM(LOWER(column_name) = 'name_key') = 1
  ) x);
SET @sql := IF(@cp_tbl IS NULL OR @drop IS NULL,
  'SELECT ''提示：未发现 risk_counterparty 旧唯一索引，跳过'' AS message',
  @drop);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.4 新增组织索引
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = @cp_tbl AND index_name = 'idx_risk_counterparty_org');
SET @sql := IF(@cp_tbl IS NULL OR @cp_org IS NULL,
  'SELECT ''提示：risk_counterparty 或 org_id 不存在，跳过组织索引'' AS message',
  IF(@idx > 0,
     'SELECT ''提示：idx_risk_counterparty_org 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @cp_tbl, '` ADD INDEX `idx_risk_counterparty_org` (`', @cp_org, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1.5 新增组织维度唯一键 (org_id, name_key)，按列组合判断是否已存在
SET @has_new := (SELECT COUNT(*) FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @cp_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 2
       AND SUM(LOWER(column_name) = 'org_id') = 1
       AND SUM(LOWER(column_name) = 'name_key') = 1
  ) x);
SET @sql := IF(@cp_tbl IS NULL OR @cp_org IS NULL OR @cp_name_key IS NULL,
  'SELECT ''提示：risk_counterparty 缺少 org_id 或 name_key，跳过唯一键'' AS message',
  IF(@has_new > 0,
     'SELECT ''提示：risk_counterparty 组织唯一键已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @cp_tbl, '` ADD UNIQUE KEY `uk_risk_counterparty_org_name` (`', @cp_org, '`, `', @cp_name_key, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 2. 标签挂载 risk_tag_link
-- =============================================================================

-- 2.1 增加 org_id
SET @sql := IF(@rtl_tbl IS NULL,
  'SELECT ''提示：risk_tag_link 表不存在，跳过 org_id 字段'' AS message',
  IF(@rtl_org IS NOT NULL,
     'SELECT ''提示：risk_tag_link.org_id 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @rtl_tbl, '` ADD COLUMN `org_id` bigint DEFAULT NULL COMMENT ''数据归属组织 id；手工标签=创建人组织，系统标签 NULL=全局''')
  ));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @rtl_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @rtl_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);

-- 2.2 手工标签按创建人组织回填，创建人无组织时兜底根组织 1
SET @sql := IF(@rtl_tbl IS NULL OR @rtl_org IS NULL OR @rtl_source IS NULL OR @rtl_owner IS NULL,
  'SELECT ''提示：risk_tag_link 缺少 org_id/owner_user_id/source，跳过组织回填'' AS message',
  IF(@user_tbl IS NULL OR @user_id_col IS NULL OR @user_org_col IS NULL,
     CONCAT('UPDATE `', @rtl_tbl, '` SET `', @rtl_org, '` = 1 WHERE `', @rtl_source, '` = ''MANUAL'' AND `', @rtl_org, '` IS NULL'),
     CONCAT('UPDATE `', @rtl_tbl, '` l LEFT JOIN `', @user_tbl, '` u ON u.`', @user_id_col,
            '` = l.`', @rtl_owner, '` SET l.`', @rtl_org, '` = COALESCE(u.`', @user_org_col,
            '`, 1) WHERE l.`', @rtl_source, '` = ''MANUAL'' AND l.`', @rtl_org, '` IS NULL')
  ));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.3 同组织内重复手工标签去重，保留 id 最小的一条
SET @sql := IF(@rtl_tbl IS NULL OR @rtl_org IS NULL OR @rtl_source IS NULL
  OR @rtl_subject_type IS NULL OR @rtl_subject_key IS NULL OR @rtl_tag_id IS NULL OR @rtl_id IS NULL,
  'SELECT ''提示：risk_tag_link 缺少去重所需列，跳过去重'' AS message',
  CONCAT('DELETE l1 FROM `', @rtl_tbl, '` l1 JOIN `', @rtl_tbl, '` l2',
         ' ON l1.`', @rtl_subject_type, '` = l2.`', @rtl_subject_type, '`',
         ' AND l1.`', @rtl_subject_key, '` = l2.`', @rtl_subject_key, '`',
         ' AND l1.`', @rtl_tag_id, '` = l2.`', @rtl_tag_id, '`',
         ' AND l1.`', @rtl_source, '` = ''MANUAL'' AND l2.`', @rtl_source, '` = ''MANUAL''',
         ' AND COALESCE(l1.`', @rtl_org, '`, 1) = COALESCE(l2.`', @rtl_org, '`, 1)',
         ' AND l1.`', @rtl_id, '` > l2.`', @rtl_id, '`'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.4 删除任意旧唯一索引（旧口径：subject_type + subject_key + tag_id + owner_user_id）
SET @drop := (SELECT CONCAT('ALTER TABLE `', @rtl_tbl, '` ',
    GROUP_CONCAT(CONCAT('DROP INDEX `', index_name, '`') SEPARATOR ', '))
  FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @rtl_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 4
       AND SUM(LOWER(column_name) = 'subject_type') = 1
       AND SUM(LOWER(column_name) = 'subject_key') = 1
       AND SUM(LOWER(column_name) = 'tag_id') = 1
       AND SUM(LOWER(column_name) = 'owner_user_id') = 1
  ) x);
SET @sql := IF(@rtl_tbl IS NULL OR @drop IS NULL,
  'SELECT ''提示：未发现 risk_tag_link 旧唯一索引，跳过'' AS message',
  @drop);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.5 组织索引
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = @rtl_tbl AND index_name = 'idx_risk_tag_link_org');
SET @sql := IF(@rtl_tbl IS NULL OR @rtl_org IS NULL,
  'SELECT ''提示：risk_tag_link 或 org_id 不存在，跳过组织索引'' AS message',
  IF(@idx > 0,
     'SELECT ''提示：idx_risk_tag_link_org 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @rtl_tbl, '` ADD INDEX `idx_risk_tag_link_org` (`', @rtl_org, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2.6 新增组织唯一键 (subject_type, subject_key, tag_id, org_id)
SET @has_new := (SELECT COUNT(*) FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @rtl_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 4
       AND SUM(LOWER(column_name) = 'subject_type') = 1
       AND SUM(LOWER(column_name) = 'subject_key') = 1
       AND SUM(LOWER(column_name) = 'tag_id') = 1
       AND SUM(LOWER(column_name) = 'org_id') = 1
  ) x);
SET @sql := IF(@rtl_tbl IS NULL OR @rtl_org IS NULL OR @rtl_subject_type IS NULL
  OR @rtl_subject_key IS NULL OR @rtl_tag_id IS NULL,
  'SELECT ''提示：risk_tag_link 缺少组织唯一键所需列，跳过'' AS message',
  IF(@has_new > 0,
     'SELECT ''提示：risk_tag_link 组织唯一键已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @rtl_tbl, '` ADD UNIQUE KEY `uk_risk_tag_link_org` (`',
            @rtl_subject_type, '`, `', @rtl_subject_key, '`, `', @rtl_tag_id, '`, `', @rtl_org, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 3. 网贷平台关键词库 lending_platform
-- =============================================================================

-- 3.1 增加 org_id
SET @sql := IF(@lp_tbl IS NULL,
  'SELECT ''提示：lending_platform 表不存在，跳过 org_id 字段'' AS message',
  IF(@lp_org IS NOT NULL,
     'SELECT ''提示：lending_platform.org_id 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @lp_tbl, '` ADD COLUMN `org_id` bigint DEFAULT NULL COMMENT ''数据归属组织 id''')
  ));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @lp_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @lp_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);

-- 3.2 删除任意旧唯一索引（旧口径：仅 name 一列）
SET @drop := (SELECT CONCAT('ALTER TABLE `', @lp_tbl, '` ',
    GROUP_CONCAT(CONCAT('DROP INDEX `', index_name, '`') SEPARATOR ', '))
  FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @lp_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 1 AND SUM(LOWER(column_name) = 'name') = 1
  ) x);
SET @sql := IF(@lp_tbl IS NULL OR @drop IS NULL,
  'SELECT ''提示：未发现 lending_platform 旧 name 唯一索引，跳过'' AS message',
  @drop);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3.3 现有全局平台复制到所有组织各一份（列存在才拼入，兼容时间列命名差异）
SET @lp_cols := IF(@lp_tbl IS NULL OR @org_tbl IS NULL OR @lp_name IS NULL
  OR @lp_org IS NULL OR @org_id_col IS NULL,
  NULL,
  CONCAT('`', @lp_name, '`',
    IF(@lp_keywords IS NOT NULL, CONCAT(',`', @lp_keywords, '`'), ''),
    IF(@lp_enabled IS NOT NULL, CONCAT(',`', @lp_enabled, '`'), ''),
    IF(@lp_builtin IS NOT NULL, CONCAT(',`', @lp_builtin, '`'), ''),
    IF(@lp_remark IS NOT NULL, CONCAT(',`', @lp_remark, '`'), ''),
    IF(@lp_create IS NOT NULL, CONCAT(',`', @lp_create, '`'), ''),
    IF(@lp_update IS NOT NULL, CONCAT(',`', @lp_update, '`'), ''),
    CONCAT(',`', @lp_org, '`')));

SET @lp_vals := IF(@lp_cols IS NULL, NULL,
  CONCAT('p.`', @lp_name, '`',
    IF(@lp_keywords IS NOT NULL, CONCAT(',p.`', @lp_keywords, '`'), ''),
    IF(@lp_enabled IS NOT NULL, CONCAT(',p.`', @lp_enabled, '`'), ''),
    IF(@lp_builtin IS NOT NULL, CONCAT(',p.`', @lp_builtin, '`'), ''),
    IF(@lp_remark IS NOT NULL, CONCAT(',p.`', @lp_remark, '`'), ''),
    IF(@lp_create IS NOT NULL, ',NOW()', ''),
    IF(@lp_update IS NOT NULL, ',NOW()', ''),
    CONCAT(',o.`', @org_id_col, '`')));

SET @sql := IF(@lp_cols IS NULL,
  'SELECT ''提示：lending_platform 或 organization 缺少必要列，跳过平台复制'' AS message',
  CONCAT('INSERT INTO `', @lp_tbl, '` (', @lp_cols, ') SELECT ', @lp_vals,
         ' FROM `', @lp_tbl, '` p CROSS JOIN `', @org_tbl, '` o',
         ' LEFT JOIN `', @lp_tbl, '` x ON x.`', @lp_org, '` = o.`', @org_id_col,
         '` AND x.`', @lp_name, '` = p.`', @lp_name, '`',
         ' WHERE p.`', @lp_org, '` IS NULL AND x.`', @lp_name, '` IS NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3.4 删除全局平台行
SET @sql := IF(@lp_tbl IS NULL OR @lp_org IS NULL,
  'SELECT ''提示：lending_platform 或 org_id 不存在，跳过全局行清理'' AS message',
  CONCAT('DELETE FROM `', @lp_tbl, '` WHERE `', @lp_org, '` IS NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3.5 组织索引
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = @lp_tbl AND index_name = 'idx_lending_platform_org');
SET @sql := IF(@lp_tbl IS NULL OR @lp_org IS NULL,
  'SELECT ''提示：lending_platform 或 org_id 不存在，跳过组织索引'' AS message',
  IF(@idx > 0,
     'SELECT ''提示：idx_lending_platform_org 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @lp_tbl, '` ADD INDEX `idx_lending_platform_org` (`', @lp_org, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3.6 组织唯一键 (org_id, name)
SET @has_new := (SELECT COUNT(*) FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @lp_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 2
       AND SUM(LOWER(column_name) = 'org_id') = 1
       AND SUM(LOWER(column_name) = 'name') = 1
  ) x);
SET @sql := IF(@lp_tbl IS NULL OR @lp_org IS NULL OR @lp_name IS NULL,
  'SELECT ''提示：lending_platform 缺少 org_id 或 name，跳过唯一键'' AS message',
  IF(@has_new > 0,
     'SELECT ''提示：lending_platform 组织唯一键已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @lp_tbl, '` ADD UNIQUE KEY `uk_lending_platform_org_name` (`',
            @lp_org, '`, `', @lp_name, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 4. 网贷命中记录 lending_transaction_match
-- =============================================================================

-- 4.1 增加 org_id
SET @sql := IF(@ltm_tbl IS NULL,
  'SELECT ''提示：lending_transaction_match 表不存在，跳过 org_id 字段'' AS message',
  IF(@ltm_org IS NOT NULL,
     'SELECT ''提示：lending_transaction_match.org_id 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @ltm_tbl, '` ADD COLUMN `org_id` bigint DEFAULT NULL COMMENT ''数据归属组织 id''')
  ));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ltm_org := (SELECT column_name FROM information_schema.columns
  WHERE table_schema = @db AND table_name = @ltm_tbl
    AND LOWER(column_name) = 'org_id' LIMIT 1);

-- 4.2 派生数据清空
SET @sql := IF(@ltm_tbl IS NULL,
  'SELECT ''提示：lending_transaction_match 表不存在，跳过数据清理'' AS message',
  CONCAT('DELETE FROM `', @ltm_tbl, '`'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4.3 删除任意旧唯一索引（旧口径：仅交易流水列一列）
SET @drop := (SELECT CONCAT('ALTER TABLE `', @ltm_tbl, '` ',
    GROUP_CONCAT(CONCAT('DROP INDEX `', index_name, '`') SEPARATOR ', '))
  FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @ltm_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 1
       AND SUM(LOWER(column_name) IN ('transaction_id', 'transactionid')) = 1
  ) x);
SET @sql := IF(@ltm_tbl IS NULL OR @drop IS NULL,
  'SELECT ''提示：未发现 lending_transaction_match 旧唯一索引，跳过'' AS message',
  @drop);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4.4 组织索引
SET @idx := (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = @db AND table_name = @ltm_tbl AND index_name = 'idx_lending_match_org');
SET @sql := IF(@ltm_tbl IS NULL OR @ltm_org IS NULL,
  'SELECT ''提示：lending_transaction_match 或 org_id 不存在，跳过组织索引'' AS message',
  IF(@idx > 0,
     'SELECT ''提示：idx_lending_match_org 已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @ltm_tbl, '` ADD INDEX `idx_lending_match_org` (`', @ltm_org, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4.5 组织唯一键 (org_id, 实际交易流水列)
SET @has_new := (SELECT COUNT(*) FROM (
    SELECT index_name
    FROM information_schema.statistics
    WHERE table_schema = @db AND table_name = @ltm_tbl AND non_unique = 0
    GROUP BY index_name
    HAVING COUNT(*) = 2
       AND SUM(LOWER(column_name) = 'org_id') = 1
       AND SUM(LOWER(column_name) IN ('transaction_id', 'transactionid')) = 1
  ) x);
SET @sql := IF(@ltm_tbl IS NULL OR @ltm_org IS NULL OR @ltm_tx IS NULL,
  'SELECT ''提示：lending_transaction_match 缺少 org_id 或交易流水列，跳过唯一键；请检查实际列名'' AS message',
  IF(@has_new > 0,
     'SELECT ''提示：lending_transaction_match 组织唯一键已存在，跳过'' AS message',
     CONCAT('ALTER TABLE `', @ltm_tbl, '` ADD UNIQUE KEY `uk_lending_match_org_tx` (`',
            @ltm_org, '`, `', @ltm_tx, '`)')));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 5. 执行结果核对
-- =============================================================================

-- 5.1 组织字段实际列名
SELECT table_name, column_name, column_type, is_nullable
FROM information_schema.columns
WHERE table_schema = @db
  AND LOWER(table_name) IN ('risk_counterparty', 'risk_tag_link', 'lending_platform', 'lending_transaction_match')
  AND LOWER(column_name) = 'org_id'
ORDER BY table_name;

-- 5.2 唯一索引实际列组合
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM information_schema.statistics
WHERE table_schema = @db
  AND LOWER(table_name) IN ('risk_counterparty', 'risk_tag_link', 'lending_platform', 'lending_transaction_match')
  AND non_unique = 0
GROUP BY table_name, index_name
ORDER BY table_name, index_name;

-- 5.3 各组织平台数量
SET @sql := IF(@lp_tbl IS NULL OR @lp_org IS NULL OR @org_tbl IS NULL OR @org_id_col IS NULL,
  'SELECT ''提示：lending_platform 或 organization 不完整，跳过平台数量核对'' AS message',
  CONCAT('SELECT o.`', @org_id_col, '` AS org_id, COUNT(p.`', @lp_name,
         '`) AS platform_count FROM `', @org_tbl, '` o LEFT JOIN `', @lp_tbl,
         '` p ON p.`', @lp_org, '` = o.`', @org_id_col,
         '` GROUP BY o.`', @org_id_col, '` ORDER BY o.`', @org_id_col, '`'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5.4 组织归属空值核查（表或列不存在时输出提示）
SET @sql := IF(@lp_tbl IS NULL OR @lp_org IS NULL,
  'SELECT ''lending_platform'' AS table_name, -1 AS null_org_rows, ''表或列不存在'' AS note',
  CONCAT('SELECT ''', @lp_tbl, ''' AS table_name, COUNT(*) AS null_org_rows, ''OK'' AS note FROM `',
         @lp_tbl, '` WHERE `', @lp_org, '` IS NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@ltm_tbl IS NULL OR @ltm_org IS NULL,
  'SELECT ''lending_transaction_match'' AS table_name, -1 AS null_org_rows, ''表或列不存在'' AS note',
  CONCAT('SELECT ''', @ltm_tbl, ''' AS table_name, COUNT(*) AS null_org_rows, ''OK'' AS note FROM `',
         @ltm_tbl, '` WHERE `', @ltm_org, '` IS NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF(@rtl_tbl IS NULL OR @rtl_org IS NULL OR @rtl_source IS NULL,
  'SELECT ''risk_tag_link_manual'' AS table_name, -1 AS null_org_rows, ''表或列不存在'' AS note',
  CONCAT('SELECT ''', @rtl_tbl, '_manual'' AS table_name, COUNT(*) AS null_org_rows, ''OK'' AS note FROM `',
         @rtl_tbl, '` WHERE `', @rtl_source, '` = ''MANUAL'' AND `', @rtl_org, '` IS NULL'));
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 6. 服务器结构诊断查询（如需人工核对，可单独执行）
-- -----------------------------------------------------------------------------
-- SELECT table_name, column_name, column_type, is_nullable
-- FROM information_schema.columns
-- WHERE table_schema = DATABASE()
--   AND table_name IN ('risk_counterparty','risk_tag_link','lending_platform','lending_transaction_match')
-- ORDER BY table_name, ordinal_position;
--
-- SELECT table_name, index_name, non_unique,
--        GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
-- FROM information_schema.statistics
-- WHERE table_schema = DATABASE()
--   AND table_name IN ('risk_counterparty','risk_tag_link','lending_platform','lending_transaction_match')
-- GROUP BY table_name, index_name, non_unique
-- ORDER BY table_name, index_name;
--
-- SELECT table_name FROM information_schema.tables
-- WHERE table_schema = DATABASE()
--   AND table_name IN ('risk_counterparty','risk_tag_link','lending_platform','lending_transaction_match','organization','user');
-- =============================================================================
