-- =============================================================================
-- 菜单同步脚本：本地 admin3 -> 服务器 admin3
-- -----------------------------------------------------------------------------
-- 目标数据库类型：MySQL 8.0.16+（resource 表含 CHECK 约束），InnoDB / utf8mb4
-- 源库：127.0.0.1:3306/admin3（项目 application.yml 配置的本地库）
-- 导出时间：2026-11-19
--
-- 同步范围（本地存在、服务器 data.sql 基线中缺失的菜单/按钮）：
--   id=39  网贷分析        MENU   permission=lending:view    url=/lending-analysis
--   id=40  网贷关键词库    MENU   permission=lending:manage  url=/lending-platforms
--   id=41  查看网贷统计    BUTTON permission=lending:view    parent=39
--   id=42  维护网贷关键词  BUTTON permission=lending:manage  parent=40
--   id=43  风险数据库      MENU   permission=risk:view       url=/risk-database
--
-- 说明：
--   1. 不写死主键 id。服务器自增位点可能不同，直接用 AUTO_INCREMENT 生成，
--      再通过 permission/type 反查实际 id，供按钮与 role_resource 引用，
--      避免与服务器已有 id 冲突。父级先插入、子级后插入，保证引用顺序。
--   2. 所有 DML 均为幂等写法：菜单按 permission+type 判重，按钮按父级+名称判重，
--      角色授权按 role_resource 联合主键 INSERT IGNORE 判重，可重复执行。
--   3. 只新增上述 5 条资源及其授权，不修改服务器已有菜单、按钮和其他数据。
--   4. 执行前请先备份服务器库（建议至少备份 resource、role_resource）：
--      mysqldump -u<user> -p admin3 resource role_resource > backup_menu_$(date +%F).sql
--   5. 若服务器角色名称被改过，请按实际情况调整第 5 节的 role.name 条件。
-- =============================================================================

SET NAMES utf8mb4;

-- =============================================================================
-- 1. DDL：仅当表不存在时创建，已存在则跳过，不改动现有表结构和数据
-- =============================================================================

CREATE TABLE IF NOT EXISTS `role` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `available` bit(1) DEFAULT NULL,
  `description` varchar(255) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_8sewwnpamngi6b1dwaa88askk` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `resource` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `icon` varchar(255) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `parent_ids` varchar(255) DEFAULT NULL,
  `permission` varchar(255) DEFAULT NULL,
  `type` tinyint DEFAULT NULL COMMENT '0=菜单 1=按钮',
  `url` varchar(255) DEFAULT NULL,
  `parent_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FKs2byvqo0b2enh3rltln5mmvyl` (`parent_id`),
  CONSTRAINT `FKs2byvqo0b2enh3rltln5mmvyl` FOREIGN KEY (`parent_id`) REFERENCES `resource` (`id`),
  CONSTRAINT `resource_chk_1` CHECK ((`type` between 0 and 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS `role_resource` (
  `role_id` bigint NOT NULL,
  `resource_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`, `resource_id`),
  KEY `FKr2orp5em3dob6f299ra9oyexr` (`resource_id`),
  CONSTRAINT `FKh8lunkrwoyio367ec8y12bis1` FOREIGN KEY (`role_id`) REFERENCES `role` (`id`),
  CONSTRAINT `FKr2orp5em3dob6f299ra9oyexr` FOREIGN KEY (`resource_id`) REFERENCES `resource` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- =============================================================================
-- 2. 前置：取根节点 id（permission='*'），并初始化各菜单/按钮变量
-- =============================================================================

SET @root_id := (SELECT `id` FROM `resource` WHERE `permission` = '*' ORDER BY `id` LIMIT 1);

-- =============================================================================
-- 3. 插入本地新增的菜单（type=0）——按 permission+type 判重
-- =============================================================================

-- 3.1 网贷分析
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT 'Coin', '网贷分析', NULL, 'lending:view', 0, '/lending-analysis', @root_id
FROM DUAL
WHERE @root_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource` WHERE `permission` = 'lending:view' AND `type` = 0
  );

-- 3.2 网贷关键词库
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT 'Collection', '网贷关键词库', NULL, 'lending:manage', 0, '/lending-platforms', @root_id
FROM DUAL
WHERE @root_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource` WHERE `permission` = 'lending:manage' AND `type` = 0
  );

-- 3.3 风险数据库
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT 'DataAnalysis', '风险数据库', NULL, 'risk:view', 0, '/risk-database', @root_id
FROM DUAL
WHERE @root_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource` WHERE `permission` = 'risk:view' AND `type` = 0
  );

-- 取实际 id（兼容服务器已存在同权限菜单的情况）
SET @menu_lending     := (SELECT `id` FROM `resource` WHERE `permission` = 'lending:view'   AND `type` = 0 ORDER BY `id` LIMIT 1);
SET @menu_lending_mgr := (SELECT `id` FROM `resource` WHERE `permission` = 'lending:manage' AND `type` = 0 ORDER BY `id` LIMIT 1);
SET @menu_risk        := (SELECT `id` FROM `resource` WHERE `permission` = 'risk:view'      AND `type` = 0 ORDER BY `id` LIMIT 1);

-- =============================================================================
-- 4. 插入按钮权限（type=1）——按父级 + permission + type + 名称判重
-- =============================================================================

-- 4.1 查看网贷统计（挂在「网贷分析」下）
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT NULL, '查看网贷统计', NULL, 'lending:view', 1, NULL, @menu_lending
FROM DUAL
WHERE @menu_lending IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource`
    WHERE `parent_id` = @menu_lending
      AND `permission` = 'lending:view'
      AND `type` = 1
      AND `name` = '查看网贷统计'
  );

-- 4.2 维护网贷关键词（挂在「网贷关键词库」下）
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT NULL, '维护网贷关键词', NULL, 'lending:manage', 1, NULL, @menu_lending_mgr
FROM DUAL
WHERE @menu_lending_mgr IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource`
    WHERE `parent_id` = @menu_lending_mgr
      AND `permission` = 'lending:manage'
      AND `type` = 1
      AND `name` = '维护网贷关键词'
  );

-- 取实际按钮 id
SET @btn_lending_view := (SELECT `id` FROM `resource` WHERE `parent_id` = @menu_lending     AND `permission` = 'lending:view'   AND `type` = 1 ORDER BY `id` LIMIT 1);
SET @btn_lending_mgr  := (SELECT `id` FROM `resource` WHERE `parent_id` = @menu_lending_mgr AND `permission` = 'lending:manage' AND `type` = 1 ORDER BY `id` LIMIT 1);

-- =============================================================================
-- 5. 角色授权（role_resource）——按本地现状同步，联合主键幂等去重
--    本地现状：超级管理员=5 条；开发者、普通用户=网贷分析/网贷关键词库/风险数据库
-- =============================================================================

-- 5.1 超级管理员：5 条全授权
INSERT IGNORE INTO `role_resource` (`role_id`, `resource_id`)
SELECT r.`id`, res.`id`
FROM `role` r
JOIN `resource` res
  ON res.`id` IN (@menu_lending, @menu_lending_mgr, @btn_lending_view, @btn_lending_mgr, @menu_risk)
WHERE r.`name` = '超级管理员';

-- 5.2 开发者：网贷分析、网贷关键词库、风险数据库
INSERT IGNORE INTO `role_resource` (`role_id`, `resource_id`)
SELECT r.`id`, res.`id`
FROM `role` r
JOIN `resource` res
  ON res.`id` IN (@menu_lending, @menu_lending_mgr, @menu_risk)
WHERE r.`name` = '开发者';

-- 5.3 普通用户：网贷分析、网贷关键词库、风险数据库
INSERT IGNORE INTO `role_resource` (`role_id`, `resource_id`)
SELECT r.`id`, res.`id`
FROM `role` r
JOIN `resource` res
  ON res.`id` IN (@menu_lending, @menu_lending_mgr, @menu_risk)
WHERE r.`name` = '普通用户';

-- =============================================================================
-- 6. 执行结果核对
-- =============================================================================

-- 6.1 应返回 5 行菜单/按钮
SELECT `id`, `name`, `permission`, `type`, `url`, `parent_id`
FROM `resource`
WHERE (`permission` = 'lending:view'   AND `type` = 0)
   OR (`permission` = 'lending:manage' AND `type` = 0)
   OR (`permission` = 'risk:view'      AND `type` = 0)
   OR (`name` IN ('查看网贷统计', '维护网贷关键词') AND `type` = 1)
ORDER BY `type`, `id`;

-- 6.2 角色授权明细：超级管理员 5 行、开发者 3 行、普通用户 3 行
SELECT r.`name` AS role_name, res.`id` AS resource_id, res.`name` AS resource_name, res.`permission`
FROM `role_resource` rr
JOIN `role` r ON r.`id` = rr.`role_id`
JOIN `resource` res ON res.`id` = rr.`resource_id`
WHERE res.`id` IN (SELECT `id` FROM `resource`
                   WHERE (`permission` IN ('lending:view', 'lending:manage', 'risk:view') AND `type` = 0)
                      OR (`name` IN ('查看网贷统计', '维护网贷关键词') AND `type` = 1))
ORDER BY r.`name`, res.`id`;
