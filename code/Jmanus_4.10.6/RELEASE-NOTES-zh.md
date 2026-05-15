# Lynxe 发布说明（中文）

## 概述

本文档基于当前项目的 Git 提交记录整理，涵盖自 V4.10.6 以来的功能增强与修复。

---

## 新功能

### 待办任务管理（Todo）

- 在 **PlanningFactory** 中集成 **TodoStorageService** 与 **TodoWriteTool**，支持任务/待办管理能力。
- 新增 `waitingForResponse` 等中英文文案，优化执行过程中的用户反馈。

### 即发即弃执行支持定时调度（Fire-and-Forget Execution Timer）

- **工具重命名**：`start-async-execution` 更名为 `fire-and-forget-execution`，更准确体现「发射后不管」的语义。
- **可选定时参数**：支持通过可选参数 `day`、`hour`、`minute`、`second` 延迟执行已注册的批量任务。
- **立即执行**：未指定任何定时参数时保持原有行为，立即在后台执行。
- **调度实现**：引入 `ScheduledExecutorService`，在指定延迟后触发执行，并更新中英文 i18n 描述与参数说明。

---

## 功能优化

### 执行控制器参数可选化

- **ExecutionController** 中取消执行参数的必填校验及相关错误提示。
- 参数处理逻辑简化，未填写时不再强制拦截，提升使用灵活性。

### 动态 Agent 与 MCP 连接管理

- **DynamicAgent** 中增强 **buildPromptSummary**：输出完整 JSON 形式的 prompt 消息（含 tool calls 与参数），并完善文档与 JSON 序列化失败时的错误处理。
- **McpCacheManager** 优化服务连接管理：
  - 当未找到服务器配置时输出更明确的告警日志（便于发现配置被删除等情况）。
  - 新增 **cleanupDeletedServer**，对已删除的服务器执行资源清理（取消健康检查、关闭连接）。
  - 在重建连接时清理配置中已不存在的服务器对应连接，提升资源管理与稳定性。

---

## 前端与体验

### RightPanel 与资源展示

- 优化 RightPanel 中上下文使用率等计算的代码格式与可读性。
- 数据库清理相关视图：调整表格卡片结构，统一 div 层级，提升界面清晰度。

### 资源与构建

- 更新 **index.html** 中的脚本引用至新版本资源。
- 移除已废弃的 JavaScript 文件（如与 CSS mode、Handlebars、Freemarker 相关的脚本及 source map），减轻构建产物体积。

---

## 代码质量与可维护性

- 对 **DynamicAgent**、**DatabaseCleanupService**、**McpCacheManager**、**StartAsyncExecutionTool**、**TodoStorageService**、**TodoWriteTool** 等类进行格式与换行、缩进调整，提升可读性。
- 统一并完善部分方法的注释与文档，便于后续维护。

---

## 版本与依赖

- 当前项目版本：**4.10.6**（见 `pom.xml`）。
- 上述内容对应自 V4.10.6 之后至当前分支的提交变更。

---

## 使用与升级建议

1. **即发即弃 + 定时**：若需延迟执行，在调用 `fire-and-forget-execution` 时传入 `day`/`hour`/`minute`/`second`（可只填部分），不传则立即执行。
2. **数据库清理**：在配置页中进入新增的数据库清理配置区域，按界面提示管理历史记录。
3. **待办与任务**：通过规划工厂中注册的 Todo 相关工具与存储服务使用任务管理能力。
4. **MCP**：若调整了 MCP 服务器配置，重启或触发重建后，已删除的服务器连接会被自动清理。

---

_本发布说明由项目提交历史自动整理，如有细节差异请以实际代码与配置为准。_
