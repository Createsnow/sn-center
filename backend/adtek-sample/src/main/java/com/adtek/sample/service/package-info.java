/**
 * 用例接口层：每个公开方法说明权限、事务、输入来源、失败与返回含义。类名以 Service 结尾，只放接口。
 *
 * <p>供本模块 controller 与 server 装配使用；其他业务模块不能依赖本模块（Maven Enforcer），本模块向其他模块提供的能力通过在 service.impl 中实现
 * framework 端口（*Port）给出。
 */
package com.adtek.sample.service;
