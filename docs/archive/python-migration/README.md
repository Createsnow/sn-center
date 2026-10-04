# Python 迁移归档索引

归档日期：2026-10-04。此目录冻结迁移历史，不作为当前业务状态入口。日常开发返回[当前导航](../../README.md)。原文里“待迁移/待归档”等是对应阶段事实，不能据此覆盖新的交付结论。

## 追溯入口

| 内容 | 原始记录 |
| --- | --- |
| 来源与范围 | [范围规则决定](docs/plans/SN补迁范围与规则决定.md)、[93 文件差异映射](docs/plans/SN原型更新差异清单.md)、[来源逐文件清单](docs/reports/evidence/20261004-incremental/source-delta.json) |
| 数据处置 | [不迁移旧数据决定](docs/plans/旧数据处置范围说明.md)、[受限旧库工具契约](docs/16-旧库快照暂存与主数据迁移.md) |
| 功能验收版本 | [SN迁移总结与验收记录](docs/reports/2026-10-04-SN迁移总结与验收记录.md)、[661 文件受检摘要](docs/reports/evidence/20261004-sn-acceptance/implementation-manifest.json) |
| 原执行计划 | [六步计划](docs/plans/2026-10-04-SN补迁与Adtek开发框架建设实施计划.md) |
| v1.1 原文 | [技术栈](docs/references/baselines/技术栈和工程结构（v1.1）.md)、[注释规范](docs/references/baselines/AI%20生成代码注释规范（v1.1）.md)、[迁移方案](docs/references/baselines/SN项目迁移方案（v1.1）.md) |
| 历史工作包与报告 | [原文档导航](docs/README.md)、[原30项矩阵](docs/11-完整功能迁移清单.md)、[阶段报告目录](docs/reports) |
| 原入口与接续 | [README快照](README.original.md)、[AGENTS快照](AGENTS.md)、[memory快照](memory) |
| 原位置与内容证明 | [file-manifest.json](file-manifest.json)：每个原路径、归档路径、字节数与 SHA-256 |
| 当前差异关闭与遗留 | [当前差异状态](../../releases/功能与差异状态.md)、[当前待办](../../releases/待办与验收边界.md) |

范围共 310 文件：原 docs 全树 303 文件、memory 3 文件、README/AGENTS 2 文件、两个公开 deploy 配置快照。秘密、数据库、备份、缓存和运行日志未复制。保留 archive/docs 的内部层级，原相对引用仍能定位；原 README 单独命名 README.original.md 避免覆盖本索引，manifest 对应更新。当前正文衍生文件修正文档过时状态，原始版本保持字节不变。

归档检查由 `node tools/check-docs.mjs` 核验文件大小/SHA 与本轮冻结 SQL，另核验当前文档链接。来源基线中的原外部工作目录链接属于原始内容，不改写、不发起网络访问；按本索引找到保存副本。
