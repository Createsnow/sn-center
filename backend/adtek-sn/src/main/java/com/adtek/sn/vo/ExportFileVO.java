package com.adtek.sn.vo;

import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * 待下载的导出文件：文件名（不含扩展名）与写出函数（流式写入响应体）。
 *
 * @param baseName 文件名（不含 .xlsx）
 * @param writer 写出函数
 */
public record ExportFileVO(String baseName, Consumer<OutputStream> writer) {}
