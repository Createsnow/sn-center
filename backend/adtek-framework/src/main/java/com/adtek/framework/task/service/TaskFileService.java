package com.adtek.framework.task.service;
import java.io.*;
/** 受控文件读取用例，输出前错误保持统一JSON；流中拒绝停止后续块，不宣称已发送字节可撤回。 */
public interface TaskFileService {
    /** 当前本人/完整快照授权、READY与摘要检查；丢失/损坏409并进入RECOVERING，调用方显式恢复同任务。 */
    Download open(String id) throws IOException;
    /** 调用方必须try-with-resources关闭；每个读取块重查当前业务权限，不长期缓存旧Actor。 */
    record Download(String filename,long bytes,InputStream input,String mediaType) implements AutoCloseable {
        @Override public void close() throws IOException {input.close();}
    }
}
