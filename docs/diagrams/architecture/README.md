# 架构流动图（可拆分 · 可组合）

用 [SeeCode](https://github.com/Aryanutkarsh/seecode) 从 JSON 描述生成。每张图一个主题、不超过 9 个节点，**拆开**能单独看，**组合**起来能按角色一起看。

| 打开 | 用途 |
| --- | --- |
| [compose.html](compose.html) | **组合视图**：按角色一键选（业务 / 开发 / MES 对接方 / 运维），或自己勾选几张叠在一页；选择结果记在网址 `#v=00,05,06`，复制链接即可分享 |
| [index.html](index.html) | 全部视图的缩略目录 |
| `NN-*.html` | 单张**交互版**：悬停看上下游、点节点看详情与代码出处、选两点走一遍路径、←/→ 逐步播放、导出 PNG / SVG / GIF / MP4 |
| `NN-*.svg` / `NN-*.png` | 静态图，可放进 Word、PPT、Confluence |

## 视图

| 编号 | 视图 | 类型 | 拆分自 |
| --- | --- | --- | --- |
| 00 | 系统总览：使用方 → 前端 → 后端 → MySQL / 金蝶 | architecture | 组合入口，下面每张都是它的一块 |
| 01 | 业务主流程：同步 → 预演 → 生成 → 分配 → 领取 → 打印，转厂 | data-flow | 00 中「后端 API」做的事 |
| 02 | 后端分层：main → routes → services → db / core | dependency | 00 中「后端 API」的代码结构 |
| 03 | 服务依赖 · 订单与生成 | dependency | 02 中 services 的一半 |
| 04 | 服务依赖 · 领取、转厂与查询 | dependency | 02 中 services 的另一半 |
| 05 | SN 状态机 | state | 01 中号在各节点的状态 |
| 06 | MES 对接：登录 → 领取（幂等）→ 取明细 → 回调 | sequence | 00 中 MES → `/api/open` 这条线 |
| 07 | 部署：docker compose（web + api）+ 公司 MySQL / 金蝶 | deployment | 00 落到机器上 |

## 修改与重新生成

`NN-*.json` 是源文件，HTML / SVG / PNG 都由它生成，不要手改。改代码后按 [docs/README.md](../../README.md) §5 判断要不要同步这里（如新增服务、接口、状态）。

```bash
# 一次性：取 SeeCode（Node 20+）
git clone --depth 1 https://github.com/Aryanutkarsh/seecode.git /tmp/seecode
SC="node /tmp/seecode/skills/seecode/scripts/seecode.mjs"

$SC config init --use global                  # 首次使用
$SC render docs/diagrams/architecture         # 重新生成全部 HTML 与 index.html
$SC export docs/diagrams/architecture --formats svg,png   # 导出静态图（PNG 需本机 Chrome / Edge）
```

新增一张图：按 `NN-名称.json` 加一个文件，再在 `compose.html` 顶部的 `VIEWS`（以及需要的 `PRESETS`）里加一行。
