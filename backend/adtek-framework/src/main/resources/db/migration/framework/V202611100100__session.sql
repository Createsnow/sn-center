-- 服务端会话：JWT 只带会话号，每个请求按主键读取本表核对未吊销、未过期与口令版本；读请求不写本表。
-- 页面会话签发时吊销同一账户之前的页面会话（revoke_reason = REPLACED）；改密 / 重置为 PASSWORD；退出为 LOGOUT。
CREATE TABLE framework_session (
  id            CHAR(32)    NOT NULL COMMENT '会话号（随机 128 位，十六进制）',
  user_id       BIGINT      NOT NULL,
  kind          VARCHAR(8)  NOT NULL COMMENT 'PAGE 页面 / API 对接方',
  pwd_version   BIGINT      NOT NULL COMMENT '签发时的口令版本',
  created_at    DATETIME    NOT NULL,
  expires_at    DATETIME    NOT NULL,
  revoked_at    DATETIME    NULL,
  revoke_reason VARCHAR(16) NULL COMMENT 'REPLACED / PASSWORD / LOGOUT',
  PRIMARY KEY (id),
  KEY ix_framework_session_user (user_id, kind, revoked_at),
  KEY ix_framework_session_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='服务端会话';
