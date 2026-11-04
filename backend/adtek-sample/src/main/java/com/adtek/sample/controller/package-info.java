/**
 * HTTP 入口层：路径 /api/v1/{module}/...，只做参数绑定、校验注解和调用 Service，统一返回 Result 信封。 不访问 Mapper、不返回
 * Entity、不在此判断业务权限（按钮隐藏与注解不能代替服务用例检查）。类名以 Controller 结尾。
 */
package com.adtek.sample.controller;
