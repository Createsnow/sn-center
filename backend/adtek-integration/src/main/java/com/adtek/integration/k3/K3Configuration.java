package com.adtek.integration.k3;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 配置注册不主动连接ERP；只在显式同步/目录请求时调用。 */
@Configuration
@EnableConfigurationProperties(K3Settings.class)
public class K3Configuration {}
