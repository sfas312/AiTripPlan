# AiTripPlan：多 Agent 旅游规划学习项目

AiTripPlan 是一个围绕“旅游规划”场景构建的多 Agent 学习与实践项目。项目以 Spring AI Alibaba、AgentScope、A2A、MCP、Nacos、JManus/Lynxe 等技术为主线，展示了从基础大模型调用、工具调用、工作流编排，到分布式多 Agent 协作的完整学习路径。

本仓库适合用于：

- 学习 Spring AI Alibaba 不同版本的 Agent / MCP / Graph 能力。
- 理解多 Agent 系统如何进行任务拆分、角色分工和流程编排。
- 复盘“旅游规划”这类复杂任务为什么适合用多 Agent。
- 准备保研、考研复试、项目答辩或技术面试。

> 说明：本项目更偏学习 Demo 与原型验证，不是完整商业级旅游产品。部分模块中存在占位配置，例如 API Key、MCP Server 地址，需要按本地环境补齐后运行。

---

## 一、项目核心思想

旅游规划不是一个简单问答任务，它通常包含：

- 出发地、目的地和出行时间理解
- 自驾路线制定
- 景点行程安排
- 餐饮、美食、酒店建议
- 天气、地图、预算等外部信息查询
- 多个结果之间的整合与校验

如果只用一个大模型直接回答，容易出现信息混杂、路线不准、规划不稳定、难以调试等问题。因此本项目尝试用多 Agent 思路来拆分任务：

```text
用户旅游需求
  |
  v
主管 Agent：理解需求、拆分任务、调度专业 Agent
  |
  +-- 路线 Agent：负责自驾路线、地图相关能力
  |
  +-- 行程 Agent：负责景点、时间、游玩节奏安排
  |
  +-- 预算/汇总节点：负责费用判断和最终整合
```

项目中同时保留了两类多 Agent 设计思想：

1. **工作流编排思想**
   - 用 Graph / FlowAgent 把流程提前设计好。
   - 适合固定流程、强可控、易调试的业务。

2. **自主 Agent 思想**
   - 让 Agent 具备 ReAct 推理、工具调用、任务规划和远程协作能力。
   - 适合开放式任务和动态决策场景。

---

## 二、目录结构

```text
AiTripPlan
├── README.md
├── doc
│   ├── Prompt.md
│   ├── 文档.md
│   ├── 保研面试复习资料-多Agent项目.md
│   └── 演示：Jmanus配置百度地图MCP
│
└── code
    ├── Demo
    │   ├── SpringAi 1.0
    │   ├── SpringAi 1.1
    │   ├── SpringAi 1.1.2
    │   └── AgentScope_1.0.7
    │
    ├── AiTripPlan
    │   ├── WorkFlow-Agent-SpringAi 1.1
    │   ├── WorkFlow-Graph-SpringAi 1.1
    │   └── AiTripPlan-AgentScope
    │
    └── Jmanus_4.10.6
```

---

## 三、重点模块说明

### 1. `code/Demo`

该目录保存基础教学 Demo，用于理解框架能力。

#### `SpringAi 1.0`

主要展示 Spring AI Alibaba 1.0 阶段的基础能力：

- ChatClient 调用大模型
- MCP 基础接入
- Graph 工作流基础 Demo

适合理解“大模型应用”从普通对话到工具调用的基础过程。

#### `SpringAi 1.1`

主要展示 Spring AI Alibaba 1.1 阶段的 Agent 能力：

- A2A 协议
- MCP 注解式实现
- ReActAgent
- FlowAgent 编排思想

适合理解多 Agent 协作和 Agent-to-Agent 通信。

#### `SpringAi 1.1.2`

主要展示 Skills 能力：

- 将工具、提示词、领域流程封装成可复用 Skill
- 让 Agent 按需加载专业能力

#### `AgentScope_1.0.7`

AgentScope 基础 Demo，用于理解 AgentScope 的 Agent 构建方式。

---

### 2. `code/AiTripPlan`

这是旅游规划项目的核心实践目录。

#### `WorkFlow-Graph-SpringAi 1.1`

基于 Graph 引擎实现旅游规划工作流 Demo。

核心思想：

```text
StateGraph 定义流程图
NodeAction 定义节点逻辑
Edge 定义节点之间的跳转关系
OverAllState 保存全局状态
KeyStrategyFactory 定义状态更新策略
```

设计意图：

```text
START
  |
  v
TaskAssignmentNode     任务分发
  |
  v
RouteMakingNode        路线制定
TripPlannerNode        行程规划
  |
  v
BudgetNode             费用统筹
  |
  v
TotalBudgetEdge        预算条件判断
  |
  v
AggregationNode        结果汇总
  |
  v
END
```

核心文件：

```text
src/main/java/conf/GraphConfiguration.java
src/main/java/controller/WorkFlowController.java
src/main/java/node/RouteMakingNode.java
src/main/java/node/TripPlannerNode.java
src/main/java/node/BudgetNode.java
src/main/java/node/AggregationNode.java
src/main/java/edge/TotalBudgetEdge.java
```

注意：当前 Graph Demo 主要展示工作流编排思想，部分节点仍是示例实现，还没有完整接入真实 Agent 或地图工具。

#### `WorkFlow-Agent-SpringAi 1.1`

基于 Spring AI Alibaba Agent Framework 展示 FlowAgent 编排。

核心能力：

- `SequentialAgent`：顺序执行
- `ParallelAgent`：并行执行
- `LlmRoutingAgent`：由大模型进行任务路由
- `ReactAgent`：具备推理和工具调用能力的执行 Agent

典型设计：

```text
主管 Agent
  |
  v
LlmRoutingAgent 根据任务选择专业 Agent
  |
  v
ParallelAgent 并行执行路线规划和行程规划
  |
  v
结果汇总
```

#### `AiTripPlan-AgentScope`

这是本仓库中最接近完整多 Agent 原型的主项目。

技术栈：

- Java 17
- Spring Boot 4
- AgentScope 1.0.8
- DashScope / Qwen
- A2A
- Nacos
- MCP
- 百度地图 MCP

模块结构：

```text
AiTripPlan-AgentScope
├── pom.xml
├── commons
├── manager_agent
├── routeMaking_agent
└── tripPlanner_agent
```

模块职责：

| 模块 | 作用 |
| --- | --- |
| `commons` | 公共工具模块，封装 Agent 创建、工具注册、Nacos 客户端 |
| `manager_agent` | 主管 Agent，负责任务规划和远程 Agent 调度 |
| `routeMaking_agent` | 路线制定 Agent，接入百度地图 MCP |
| `tripPlanner_agent` | 行程规划 Agent，负责景点和日程安排 |

核心链路：

```text
用户输入旅游需求
  |
  v
ManagerAgent
  |
  +-- PlanNotebook：任务拆解
  |
  +-- RemoteAgentTool：把远程 Agent 封装成工具
  |
  +-- NacosAgentCardResolver：从 Nacos 发现远程 Agent
  |
  +-- A2aAgent：调用 RouteMakingAgent / TripPlannerAgent
  |
  v
RouteMakingAgent：调用百度地图 MCP
TripPlannerAgent：生成行程规划
```

核心文件：

```text
commons/src/main/java/utils/AgentUtils.java
commons/src/main/java/utils/ToolUtils.java
commons/src/main/java/utils/NacosUtil.java

manager_agent/src/main/java/managerAgent/agents/ManagerAgent.java
manager_agent/src/main/java/managerAgent/tool/RemoteAgentTool.java
manager_agent/src/main/java/managerAgent/plan/TripPlan.java
manager_agent/src/main/java/managerAgent/hook/planHook.java

routeMaking_agent/src/main/java/routeMakingAgent/agents/RouteMakingAgent.java
routeMaking_agent/src/main/java/routeMakingAgent/mcp/BaiduMapMCP.java

tripPlanner_agent/src/main/java/tripPlannerAgent/agents/TripPlannerAgent.java
```

---

### 3. `code/Jmanus_4.10.6`

JManus 后续改名为 Lynxe，可以理解为更完整的 Agent 应用框架。

它包含：

- 后端服务
- 前端 UI
- Agent 任务执行
- 工具系统
- MCP 配置
- 数据库配置
- 计划模板
- 任务记录与管理

在本仓库中，JManus 主要作为完整 Agent 应用框架参考，用来理解从 Demo 到产品化 Agent 平台的差异。

---

## 四、核心技术解释

### 1. Agent

Agent 不是简单的大模型调用，而是具备以下能力的智能体：

- 理解任务
- 自主推理
- 拆解步骤
- 调用工具
- 观察工具结果
- 继续决策
- 输出最终结果

在项目中，`ManagerAgent`、`RouteMakingAgent`、`TripPlannerAgent` 都是 Agent。

### 2. 多 Agent

多 Agent 是多个 Agent 按角色协作完成复杂任务。

本项目中的角色划分：

- `ManagerAgent`：主管调度
- `RouteMakingAgent`：路线制定
- `TripPlannerAgent`：行程规划

### 3. ReAct

ReAct = Reasoning + Acting。

它的执行逻辑是：

```text
思考下一步要做什么
-> 选择工具或 Agent
-> 执行动作
-> 观察结果
-> 继续推理
-> 输出答案
```

### 4. Graph 引擎

Graph 引擎是一种工作流编排方式。

它把任务拆成：

- 节点：每一步做什么
- 边：步骤之间怎么跳转
- 状态：节点之间传递什么数据

在多 Agent 场景里，Graph 节点可以封装一个 Agent，也可以是普通函数或工具调用。

### 5. FlowAgent

FlowAgent 是更高层的 Agent 编排方式。

常见类型：

- `SequentialAgent`：顺序执行
- `ParallelAgent`：并行执行
- `LlmRoutingAgent`：让大模型决定路由

### 6. A2A

A2A 是 Agent-to-Agent 通信协议。

本项目中：

```text
ManagerAgent
  |
  v
A2A
  |
  v
RouteMakingAgent / TripPlannerAgent
```

它解决的是“Agent 之间怎么通信”的问题。

### 7. Nacos

Nacos 在本项目中用于 Agent 注册与发现。

远程 Agent 启动后注册到 Nacos，主管 Agent 根据 Agent 名称找到它们。

### 8. MCP

MCP 是 Model Context Protocol。

它解决的是“Agent 如何接入外部工具”的问题。

本项目中，`RouteMakingAgent` 通过 MCP 接入百度地图能力。

### 9. PlanNotebook

`PlanNotebook` 是 AgentScope 中用于任务规划和计划跟踪的组件。

项目中 `TripPlan` 设置了：

```java
needUserConfirm(true)
maxSubtasks(5)
```

表示任务拆解后需要用户确认，并且最多拆成 5 个子任务。

### 10. Hook

Hook 用于监听 Agent 执行过程。

项目中的 `planHook` 监听：

- 用户输入
- Agent 推理过程
- 工具调用过程

它的价值是让多 Agent 系统更容易调试和观察。

---

## 五、运行环境

建议环境：

- JDK 17
- Maven 3.8+
- Node.js / pnpm，用于 JManus 前端部分
- Docker，可选，用于启动 Nacos
- IDEA，建议安装 PlantUML 插件

---

## 六、Nacos 启动

可使用 Docker 启动 Nacos：

```bash
docker pull nacos/nacos-server:v3.1.0
```

```bash
docker run --rm --name nacos \
  -e MODE=standalone \
  -e NACOS_AUTH_ENABLE=false \
  -e NACOS_AUTH_TOKEN=MjM1ZmU4NjAxMzU1NTQyYWU0MTEyYWU4ZDg3YTZiNGUK \
  -e NACOS_AUTH_IDENTITY_KEY=nacos \
  -e NACOS_AUTH_IDENTITY_VALUE=nacos \
  -p 8848:8848 \
  -p 9848:9848 \
  -p 8088:8080 \
  nacos/nacos-server:v3.1.0
```

控制台：

```text
http://127.0.0.1:8088
username: nacos
password: nacos
```

端口说明：

| 端口 | 作用 |
| --- | --- |
| 8848 | Nacos HTTP |
| 9848 | Nacos RPC |
| 8088 | Nacos 控制台 |

---

## 七、运行 AiTripPlan-AgentScope

进入主项目目录：

```bash
cd code/AiTripPlan/AiTripPlan-AgentScope
```

编译：

```bash
mvn clean package
```

启动顺序建议：

1. 启动 Nacos。
2. 启动 `routeMaking_agent`，端口 8082。
3. 启动 `tripPlanner_agent`，端口 8085。
4. 启动或运行 `manager_agent`，端口 8081 或 main 方法。

端口：

| 服务 | 端口 | 说明 |
| --- | --- | --- |
| `manager_agent` | 8081 | 用户入口 / 主管 Agent |
| `routeMaking_agent` | 8082 | 路线制定 Agent |
| `tripPlanner_agent` | 8085 | 行程规划 Agent |
| Nacos | 8848 | Agent 注册发现 |

需要补充的配置：

- DashScope API Key
- 百度地图 MCP Server SSE 地址
- 百度地图开放平台 API Key

注意：

- `AgentUtils.java` 中的 API Key 当前是占位符。
- `BaiduMapMCP.java` 中的 MCP 地址当前是占位符。
- 正式使用时应改为读取环境变量或 `application.yml`，不要把真实密钥提交到 GitHub。

---

## 八、运行 Graph 工作流 Demo

进入目录：

```bash
cd "code/AiTripPlan/WorkFlow-Graph-SpringAi 1.1/Demo_WorkFlow_SpringAi_1_1"
```

编译运行：

```bash
mvn spring-boot:run
```

接口：

```text
GET /workflow/parallel
```

核心代码：

```text
src/main/java/conf/GraphConfiguration.java
src/main/java/controller/WorkFlowController.java
```

该模块用于理解：

- `StateGraph`
- `OverAllState`
- `KeyStrategyFactory`
- `NodeAction`
- `AsyncNodeAction`
- `ConditionalEdges`

---

## 九、运行 JManus / Lynxe

进入目录：

```bash
cd code/Jmanus_4.10.6
```

后端通常使用 Maven 构建：

```bash
mvn clean package
```

前端目录：

```bash
cd ui-vue3
```

可根据 `README-dev.md`、`README-dev-en.md` 和 `ui-vue3/README.md` 查看具体开发启动方式。

---

## 十、学习路线建议

建议按以下顺序学习：

```text
1. Spring AI 基础 ChatClient
2. Function Calling / Tool Calling
3. MCP 工具接入
4. ReActAgent
5. Graph 工作流
6. FlowAgent 编排
7. A2A Agent 通信
8. Nacos 注册发现
9. AgentScope 分布式 Agent
10. JManus / Lynxe 完整 Agent 平台
```

如果用于面试复习，建议重点掌握：

- Agent 和普通大模型调用的区别
- 多 Agent 为什么适合旅游规划
- Graph 引擎如何编排流程
- A2A 和 MCP 的区别
- Nacos 在多 Agent 中的作用
- `ManagerAgent` 如何调用远程 Agent
- `RouteMakingAgent` 如何接入百度地图 MCP
- 当前项目有哪些不足和改进方向

---

## 十一、当前项目的真实边界

当前代码已经体现：

- 多 Agent 分工
- ReActAgent
- PlanNotebook
- Toolkit 工具注册
- A2A 远程 Agent 调用
- Nacos Agent 注册发现
- MCP 工具接入
- Hook 执行过程监听
- Graph 工作流编排思想
- FlowAgent 编排思想

当前代码尚未完整体现：

- 完整 RAG 流程
- 持久化 Memory
- 自动化评估
- 消融实验
- Reviewer Agent
- 结构化结果合并
- 完整异常恢复
- 商业级前端旅游规划产品

这些边界在答辩和面试中需要讲清楚，不能把 Demo 说成完整生产系统。

---

## 十二、适合面试的一句话总结

AiTripPlan 不是一个简单的“调用大模型生成旅游攻略”的项目，而是一个围绕旅游规划场景搭建的多 Agent 学习原型。它通过主管 Agent、路线 Agent、行程 Agent 的分工，结合 Graph / FlowAgent 工作流编排、A2A 通信、Nacos 服务发现和 MCP 工具接入，展示了复杂任务从单次问答走向智能体系统工程的完整思路。

---

## 十三、参考资料

- Spring AI Alibaba 文档：https://java2ai.com/
- AgentScope Java 文档：https://java.agentscope.io/zh/intro.html
- ModelScope MCP 广场：https://modelscope.cn/mcp
- 百度地图开放平台：https://lbs.baidu.com/
- JManus / Lynxe：https://github.com/spring-ai-alibaba/Lynxe

