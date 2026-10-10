-- 人员普通编辑保留登录；授权并发版本继续独立递增。
-- 复制现有版本，使升级前 JWT sv 与 Redis version 继续匹配，无需修改原密码或原安全版本。
ALTER TABLE sys_user
    ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 1
    COMMENT '登录凭据版本；改密、重置或显式撤销会话时递增';

UPDATE sys_user SET credential_version = security_version;
