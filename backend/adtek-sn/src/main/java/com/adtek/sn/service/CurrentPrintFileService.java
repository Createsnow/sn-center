package com.adtek.sn.service;

import com.adtek.framework.task.service.TaskFileService.Download;

/** 打印页下载当前成员文件；不重做打印，也不覆盖固定审计产物。 */
public interface CurrentPrintFileService {
    /** 原打印所属厂当前下载权限；一次读快照生成，空成员仍返回表头，读取块再次复核权限。 */
    Download open(String printId, String format) throws java.io.IOException;
}
