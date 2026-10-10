-- 只保存可公开的业务原因与字符串参数；不保存异常消息、SQL、凭据或堆栈。
ALTER TABLE sn_work ADD COLUMN error_params JSON NULL;
