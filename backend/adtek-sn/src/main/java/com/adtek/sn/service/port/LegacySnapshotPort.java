package com.adtek.sn.service.port;

import com.adtek.sn.dto.LegacySnapshotCommandDTO;
import com.adtek.sn.vo.LegacySnapshotManifestVO;

/** 受控旧库只读适配端口；不装配在Web应用，不读取旧配置或处理新业务命令。 */
public interface LegacySnapshotPort {
    /**
     * 对精确schema和已声明结构进行一致快照盘点，流式保存19表原始字段及摘要。
     * 调用方提供仅SELECT源账号和受限的新输出目录；不覆写已有文件，失败保留不完整文件供诊断。
     * 未知结构、SQL错误、时间歧义或资源预算超限拒绝；只有完整结束才发布manifest。
     * 问题类别阻断后续业务导入，不代表外部来源映射、历史成员或文件已经核实。
     */
    LegacySnapshotManifestVO capture(LegacySnapshotCommandDTO command);
}
