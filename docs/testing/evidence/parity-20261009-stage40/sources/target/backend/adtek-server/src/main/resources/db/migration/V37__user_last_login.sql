-- 成功登录时间只记录真实认证；历史空值保留，不推算或修改账号安全版本。
ALTER TABLE sys_user ADD COLUMN last_login_at DATETIME(6) NULL COMMENT '最后一次成功人员登录时间';
