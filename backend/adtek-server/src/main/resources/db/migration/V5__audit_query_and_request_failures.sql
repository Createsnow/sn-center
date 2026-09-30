-- 历史日志不按当前部门回填：过去所属部门无法从当前账号资料可靠还原。
ALTER TABLE sys_oper_log
 ADD COLUMN actor_dept_id BIGINT NULL COMMENT '写审计时的操作者部门快照；历史缺失/匿名为空',
 ADD COLUMN actor_username VARCHAR(64) NULL COMMENT '写审计时的不可变工号快照；未认证为空',
 ADD COLUMN request_method VARCHAR(8) NULL COMMENT '受控HTTP方法，不存请求头',
 ADD COLUMN request_route VARCHAR(160) NULL COMMENT '服务端路由模板，不存原始URL或参数',
 ADD COLUMN http_status SMALLINT NULL COMMENT '入口失败HTTP状态',
 ADD COLUMN error_code INT NULL COMMENT '已知统一错误码；未知为空',
 ADD INDEX ix_oper_log_dept_time(actor_dept_id,create_time,id),
 ADD INDEX ix_oper_log_trace(trace_id),
 ADD INDEX ix_oper_log_action_time(action,create_time,id);
