-- 权限调整：系统管理员（唯一）/ 普通操作员 / 审计员 / MES 接口账户
-- 普通操作员、审计员的授权组织统一存 sn_user_factory（至少一个）；sn_user.factory_code 只给 MES 接口账户用。
-- 存量账户的角色改名与授权组织由启动时 users.upgrade_roles() 迁移（系统管理员工号取自 .env，SQL 里拿不到）。

ALTER TABLE sn_user
  MODIFY role VARCHAR(32) NOT NULL COMMENT 'admin 系统管理员（唯一）/ operator 普通操作员 / auditor 审计员 / mes MES 接口账户',
  MODIFY factory_code VARCHAR(64) NULL COMMENT 'MES 接口账户的生产组织；普通操作员、审计员的授权组织见 sn_user_factory';

ALTER TABLE sn_user_factory COMMENT = '普通操作员、审计员的授权生产组织（至少一个）';

-- 规则所属组织：NULL = 全局规则（只有系统管理员能改、删；普通操作员只能选用）
ALTER TABLE sn_rule
  ADD COLUMN factory_code VARCHAR(64) NULL COMMENT '所属生产组织；NULL = 全局规则（只有系统管理员能改）' AFTER category_value,
  ADD KEY ix_rule_factory (factory_code);
