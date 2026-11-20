-- =============================================================================
-- 风险数据库三模块权限同步脚本
-- -----------------------------------------------------------------------------
-- 目标数据库类型：MySQL 8.0.16+（resource 表含 CHECK 约束），InnoDB / utf8mb4
-- 适用场景：本地 admin3 -> 服务器 admin3 的权限资源同步
--
-- 权限点（沿用 resource / role_resource 模型）：
--   资方数据库      菜单  risk:funder:view         按钮  risk:funder:manage
--   黑名单数据库    菜单  risk:blacklist:view      按钮  risk:blacklist:manage
--   交易对手数据库  菜单  risk:counterparty:view   按钮  risk:counterparty:manage
--
-- 说明：
--   1. 全部 DML 均为幂等写法：菜单按 permission + type 判重，按钮按
--      父级 + permission + type + 名称判重，角色授权按 role_resource 联合主键
--      INSERT IGNORE 判重，可重复执行。
--   2. 父菜单「风险数据库」权限由 risk:view 调整为 risk。风险库模块权限
--      （risk:funder:view 等）均能被 risk 前缀匹配，因此父菜单对任一模块可见；
--      同时父菜单自身不再作为接口鉴权依据。
--   3. 不写死主键 id，父级先插入、子级后插入，再通过 permission/type 反查实际 id。
--   4. 仅新增/调整风险数据库自身资源，并把新权限授予「原来已拥有风险数据库菜单」
--      的角色，不影响其他模块的既有权限。
--   5. 执行前请先备份 resource、role_resource：
--      mysqldump -u<user> -p admin3 resource role_resource > backup_risk_menu_$(date +%F).sql
--   6. 若超级管理员角色名称被修改过，请按实际情况调整第 5.2 节的 role.name 条件。
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
-- 2. 父菜单「风险数据库」：权限调整为 risk（任意 risk:* 权限均可显示父菜单）
-- =============================================================================

UPDATE `resource`
SET `permission` = 'risk'
WHERE `type` = 0
  AND `url` = '/risk-database'
  AND (`permission` = 'risk:view' OR `permission` IS NULL);

SET @root_id := (SELECT `id` FROM `resource` WHERE `permission` = '*' ORDER BY `id` LIMIT 1);

INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT 'DataAnalysis', '风险数据库', NULL, 'risk', 0, '/risk-database', @root_id
FROM DUAL
WHERE @root_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource` WHERE `type` = 0 AND `url` = '/risk-database'
  );

SET @menu_risk := (SELECT `id` FROM `resource`
                   WHERE `type` = 0 AND `url` = '/risk-database'
                   ORDER BY `id` LIMIT 1);

-- =============================================================================
-- 3. 三个模块的菜单（type=0）——按 permission + type 判重
-- =============================================================================

-- 3.1 资方数据库
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT 'Wallet', '资方数据库', NULL, 'risk:funder:view', 0, '/risk-database?tab=funder', @menu_risk
FROM DUAL
WHERE @menu_risk IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource` WHERE `permission` = 'risk:funder:view' AND `type` = 0
  );

-- 3.2 黑名单数据库
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT 'Warning', '黑名单数据库', NULL, 'risk:blacklist:view', 0, '/risk-database?tab=blacklist', @menu_risk
FROM DUAL
WHERE @menu_risk IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource` WHERE `permission` = 'risk:blacklist:view' AND `type` = 0
  );

-- 3.3 交易对手数据库
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT 'User', '交易对手数据库', NULL, 'risk:counterparty:view', 0, '/risk-database?tab=counterparty', @menu_risk
FROM DUAL
WHERE @menu_risk IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource` WHERE `permission` = 'risk:counterparty:view' AND `type` = 0
  );

-- 反查实际菜单 id（兼容服务器已有同权限菜单的情况）
SET @menu_funder       := (SELECT `id` FROM `resource` WHERE `permission` = 'risk:funder:view'       AND `type` = 0 ORDER BY `id` LIMIT 1);
SET @menu_blacklist    := (SELECT `id` FROM `resource` WHERE `permission` = 'risk:blacklist:view'    AND `type` = 0 ORDER BY `id` LIMIT 1);
SET @menu_counterparty := (SELECT `id` FROM `resource` WHERE `permission` = 'risk:counterparty:view' AND `type` = 0 ORDER BY `id` LIMIT 1);

-- =============================================================================
-- 4. 三个模块的维护按钮（type=1）——按父级 + permission + type + 名称判重
-- =============================================================================

-- 4.1 维护资方（挂在「资方数据库」下）
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT NULL, '维护资方', NULL, 'risk:funder:manage', 1, NULL, @menu_funder
FROM DUAL
WHERE @menu_funder IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource`
    WHERE `parent_id` = @menu_funder
      AND `permission` = 'risk:funder:manage'
      AND `type` = 1
      AND `name` = '维护资方'
  );

-- 4.2 维护黑名单（挂在「黑名单数据库」下）
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT NULL, '维护黑名单', NULL, 'risk:blacklist:manage', 1, NULL, @menu_blacklist
FROM DUAL
WHERE @menu_blacklist IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource`
    WHERE `parent_id` = @menu_blacklist
      AND `permission` = 'risk:blacklist:manage'
      AND `type` = 1
      AND `name` = '维护黑名单'
  );

-- 4.3 维护交易对手（挂在「交易对手数据库」下）
INSERT INTO `resource` (`icon`, `name`, `parent_ids`, `permission`, `type`, `url`, `parent_id`)
SELECT NULL, '维护交易对手', NULL, 'risk:counterparty:manage', 1, NULL, @menu_counterparty
FROM DUAL
WHERE @menu_counterparty IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `resource`
    WHERE `parent_id` = @menu_counterparty
      AND `permission` = 'risk:counterparty:manage'
      AND `type` = 1
      AND `name` = '维护交易对手'
  );

SET @btn_funder       := (SELECT `id` FROM `resource` WHERE `parent_id` = @menu_funder       AND `permission` = 'risk:funder:manage'       AND `type` = 1 ORDER BY `id` LIMIT 1);
SET @btn_blacklist    := (SELECT `id` FROM `resource` WHERE `parent_id` = @menu_blacklist    AND `permission` = 'risk:blacklist:manage'    AND `type` = 1 ORDER BY `id` LIMIT 1);
SET @btn_counterparty := (SELECT `id` FROM `resource` WHERE `parent_id` = @menu_counterparty AND `permission` = 'risk:counterparty:manage' AND `type` = 1 ORDER BY `id` LIMIT 1);

-- =============================================================================
-- 5. 角色授权（role_resource）——保持原有风险库访问角色的能力不变
-- =============================================================================

-- 5.1 记录原来已拥有「风险数据库」父菜单或 risk:view 权限的角色
DROP TEMPORARY TABLE IF EXISTS `tmp_risk_roles`;
CREATE TEMPORARY TABLE `tmp_risk_roles` (
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO `tmp_risk_roles` (`role_id`)
SELECT DISTINCT rr.`role_id`
FROM `role_resource` rr
JOIN `resource` res ON res.`id` = rr.`resource_id`
WHERE (res.`type` = 0 AND res.`url` = '/risk-database')
   OR res.`permission` = 'risk:view';

-- 5.2 原风险库角色：授予三个模块的菜单与维护按钮
INSERT IGNORE INTO `role_resource` (`role_id`, `resource_id`)
SELECT tmp.`role_id`, res.`id`
FROM `tmp_risk_roles` tmp
JOIN `resource` res
  ON res.`id` IN (@menu_funder, @menu_blacklist, @menu_counterparty,
                  @btn_funder, @btn_blacklist, @btn_counterparty);

-- 5.3 超级管理员：兜底授予全部风险库模块权限（角色名被修改时请调整此条件）
INSERT IGNORE INTO `role_resource` (`role_id`, `resource_id`)
SELECT r.`id`, res.`id`
FROM `role` r
JOIN `resource` res
  ON res.`id` IN (@menu_funder, @menu_blacklist, @menu_counterparty,
                  @btn_funder, @btn_blacklist, @btn_counterparty)
WHERE r.`name` = '超级管理员';

DROP TEMPORARY TABLE IF EXISTS `tmp_risk_roles`;

-- =============================================================================
-- 6. 执行结果核对
-- =============================================================================

-- 6.1 应返回 1 条父菜单 + 3 条模块菜单 + 3 条维护按钮
SELECT `id`, `name`, `permission`, `type`, `url`, `parent_id`
FROM `resource`
WHERE (`type` = 0 AND `url` LIKE '/risk-database%')
   OR (`permission` IN ('risk:funder:manage', 'risk:blacklist:manage', 'risk:counterparty:manage') AND `type` = 1)
ORDER BY `type`, `id`;

-- 6.2 角色授权明细：按角色查看三个模块的菜单与按钮数量
SELECT r.`name` AS role_name, COUNT(*) AS granted_count
FROM `role_resource` rr
JOIN `role` r ON r.`id` = rr.`role_id`
JOIN `resource` res ON res.`id` = rr.`resource_id`
WHERE res.`id` IN (SELECT `id` FROM `resource`
                   WHERE (`type` = 0 AND `url` LIKE '/risk-database%')
                      OR (`permission` IN ('risk:funder:manage', 'risk:blacklist:manage', 'risk:counterparty:manage') AND `type` = 1))
GROUP BY r.`id`, r.`name`
ORDER BY r.`id`;
