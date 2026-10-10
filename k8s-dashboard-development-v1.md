# Kubernetes Dashboard 开发文档 v1

整理日期：2026-10-10  
需求依据：`k8s-dashboard-api-v1.md`  
机器可读接口契约：`k8s-dashboard-openapi-v1.json`，OpenAPI 3.0.3，共 30 个 HTTP 操作。

本文面向后端、前端、测试及部署人员。目标是开发一个通过 Java 后端访问 Kubernetes 的 Dashboard，支持命名空间排障、资源查看，以及受控的 Deployment 维护。

**当前交付为开发设计及接口契约，尚未创建应用代码、连接集群或验证运行兼容性。** 原文中已确认的信息、原文接口约束和本文新增的实现建议分开说明。原文中的命令与示例仅作为需求材料，不代表已经执行。

## 1. 范围与设计基线

### 1.1 已知条件与待确认项

| 项目 | 当前结论 | 对开发的影响 |
|---|---|---|
| 部署场景 | 腾讯云 CVM 上自建 Kubernetes | 通过 API Server 访问，不接入 CVM 管理 API |
| kubelet | 原文记录 `k8s-master` 的输出为 `v1.31.10` | 只代表该 kubelet 二进制，不能推断 API Server 或全节点版本 |
| API Server | 未确认实际版本、地址、证书及网络连通性 | 确认后锁定客户端版本并做集成测试 |
| 集群数量 | 首版一个，标识建议 `main` | 保留 `clusterId` 路由与客户端注册表 |
| Metrics API | 未确认是否存在且可访问 | 功能可降级，不阻塞只读核心功能 |
| 运行身份 | 尚未确认集群外 kubeconfig 或集群内 ServiceAccount | 两种部署方式由服务端配置选择 |
| 前端技术栈、数据库、Java 依赖版本 | 原文未确定 | 采用下文建议基线，实施前冻结依赖锁文件 |

### 1.2 阶段范围

| 阶段 | 交付功能 | 不包含 |
|---|---|---|
| P0：只读排障 | 登录、权限、集群状态、命名空间、节点、Deployment、Pod、事件、日志、YAML、实时通知、可选指标 | 任意集群写入 |
| P1：受控维护 | 扩缩容、重启、更新普通容器镜像、操作状态、审计 | 任意 YAML 编辑、删除资源、自动回滚 |
| P2：后续扩展 | StatefulSet、DaemonSet、Job、CronJob、Service、Ingress、ConfigMap、PVC、HPA 只读页面 | 本版不为这些资源承诺 DTO、接口实现或写操作 |

v1 不包含 Secret 内容查看、容器终端、任意 kubectl 执行、节点驱逐、历史监控曲线和腾讯云 CVM 运维。

### 1.3 新增实现建议

以下是为了让开发可以落地而补充的方案，不是原文已确认的项目现状：

| 部分 | 建议基线 | 说明 |
|---|---|---|
| 后端 | Java 21、Spring Boot 3 系列、Spring Security 6、Maven | 作为设计基线，不宣称是最新版本；小版本和维护状态在实施时核对 |
| Kubernetes 客户端 | Fabric8 Kubernetes Client | 锁定版本后核验 API Server 兼容性和实际并发写入行为 |
| 前端 | Vue 3、TypeScript、Vite、Vue Router、Pinia | 原文未指定；业务 API 契约不依赖 Vue |
| UI 组件 | 选用一个维护中的 Vue 组件库 | 表格、抽屉、表单、确认框复用同一套组件，不在本文固定包版本 |
| 持久化 | PostgreSQL、Flyway、Spring JDBC | 保存用户、授权、operation、幂等和审计 |
| 登录 | 同源 Session、JSON 登录、CSRF | 首版采用本地用户；接入公司 SSO 另行设计 |
| 部署 | Nginx 同源代理 + 一个后端实例 | 后端重启可让用户重新登录，但任务和幂等记录必须恢复 |
| API 文档 | 随仓库维护的 OpenAPI 契约 | 前端类型与后端实现均遵守契约；契约变化进入代码评审 |

先按模块化单体开发。首版不拆微服务，不引入额外消息队列。多实例部署需要补充共享 Session、全局流量配额和任务租约方案，不能直接把单实例改成多个副本。

## 2. 系统架构与责任边界

```text
浏览器
  └─ Nginx：静态资源、/api 同源代理、SSE 代理
       └─ Java Dashboard
            ├─ Spring Security：登录、Session、CSRF
            ├─ Web：路由、参数、HTTP 与 SSE 适配
            ├─ Application：授权、查询聚合、维护流程
            ├─ Kubernetes Adapter：Fabric8、DTO 映射、上游错误转换
            │    └─ API Server / Metrics API
            └─ Persistence：用户、授权、任务、幂等、审计
                 └─ PostgreSQL
```

调用方向为 `Controller → Application Service → Adapter / Repository`。Controller 不直接操作 Fabric8，不把 Kubernetes 原始对象当成前端 DTO。

浏览器只访问 Dashboard API；集群证书、Token、kubeconfig 均由服务端管理。用户不能通过请求临时提供 API Server URL 或集群凭证。

### 2.1 项目目录

下面是**待创建的目标结构**，并不表示当前目录已有这些代码。每行 `#` 后说明对应目录、包、类或文件的职责：

```text
k8s-dashboard/                                     # 项目根目录，统一管理前端、后端、文档和部署配置
├─ README.md                                       # 项目介绍、本地启动步骤和常用开发命令
├─ docs/                                           # 开发设计、接口契约和协作约定
│  ├─ development-v1.md                             # 本开发文档，说明架构、职责、流程和验收要求
│  └─ openapi-v1.json                               # 机器可读 API 契约，用于联调和生成前端类型
├─ backend/                                        # Java 后端工程，提供 Dashboard API 并访问集群
│  ├─ pom.xml                                      # 后端依赖、编译、测试及打包配置
│  ├─ src/main/java/com/example/dashboard/          # 后端 Java 源码根包，按职责分层
│  │  ├─ DashboardApplication.java                 # 应用启动入口，装配并启动后端服务
│  │  ├─ config/                                   # 集中配置安全机制、业务参数和执行线程池
│  │  │  ├─ SecurityConfig.java                     # 配置认证、Session、CSRF 和安全错误响应
│  │  │  ├─ DashboardProperties.java                # 绑定集群、维护开关、分页和流连接等配置项
│  │  │  └─ ExecutorConfig.java                     # 配置任务执行、状态观察及流处理的线程与调度资源
│  │  ├─ security/                                 # 身份认证、权限判断及会话关联资源管理
│  │  │  ├─ JsonLoginAuthenticationFilter.java      # 解析 JSON 登录请求并接入认证、会话与安全上下文流程
│  │  │  ├─ DashboardPrincipal.java                 # 表示当前登录用户的可信身份，如用户 ID 和角色
│  │  │  ├─ PermissionEvaluator.java                # 按用户、集群、命名空间和动作计算有效权限
│  │  │  └─ SessionStreamRegistry.java              # 登记会话关联的 SSE/日志流，失效或撤权时统一关闭
│  │  ├─ web/                                      # HTTP 接入层，处理路由、参数、响应与传输格式
│  │  │  ├─ controller/                            # 对外接口入口，将请求交给应用服务处理
│  │  │  │  ├─ AuthController.java                  # 提供 CSRF、当前用户及退出等认证相关接口
│  │  │  │  ├─ ClusterController.java               # 提供集群目录、连接状态和概览查询接口
│  │  │  │  ├─ NamespaceController.java             # 提供当前用户可访问的命名空间目录接口
│  │  │  │  ├─ AccessController.java                # 提供当前范围的有效操作能力与参数限制接口
│  │  │  │  ├─ NodeController.java                  # 提供节点列表和详情接口
│  │  │  │  ├─ DeploymentController.java            # 提供 Deployment 查询、关联 Pod 及三种维护接口
│  │  │  │  ├─ PodController.java                   # 提供 Pod 列表、详情、普通日志和白名单资源 YAML 接口
│  │  │  │  ├─ EventController.java                 # 提供命名空间或指定对象 UID 的事件查询接口
│  │  │  │  ├─ MetricsController.java               # 提供节点及 Pod 当前指标与可用性接口
│  │  │  │  ├─ StreamController.java                # 提供资源变化通知和容器日志的 SSE 接口
│  │  │  │  ├─ OperationController.java             # 提供维护任务状态和执行结果查询接口
│  │  │  │  └─ AuditController.java                 # 提供授权范围内的审计列表和详情接口
│  │  │  ├─ dto/                                   # HTTP 请求与响应的数据模型，与上游原始对象分开
│  │  │  │  ├─ common/                             # 通用响应包装、分页、元数据和错误 DTO
│  │  │  │  ├─ auth/                               # 登录请求、当前用户、授权范围和 CSRF DTO
│  │  │  │  ├─ cluster/                            # 集群、命名空间、节点、概览和有效能力 DTO
│  │  │  │  ├─ workload/                           # Pod、容器、Deployment、Scale、事件及维护请求 DTO
│  │  │  │  ├─ metrics/                            # 指标用量、采样时间、窗口及可用性 DTO
│  │  │  │  └─ operation/                          # 维护任务状态、变更结果和审计记录 DTO
│  │  │  └─ error/                                 # 应用异常模型及 HTTP 错误转换
│  │  │     ├─ GlobalExceptionHandler.java          # 将 Controller 处理中的异常转换为统一错误响应
│  │  │     └─ DashboardException.java              # 携带应用错误码、HTTP 状态及可安全返回的信息
│  │  ├─ application/                              # 应用服务层，组织授权、查询聚合和维护业务流程
│  │  │  ├─ AuthService.java                        # 加载账户、检查账户状态并组装当前用户信息
│  │  │  ├─ ClusterQueryService.java                # 查询授权集群与命名空间，聚合连接状态和概览
│  │  │  ├─ ResourceQueryService.java               # 组织资源查询、授权、分页和白名单 YAML 读取
│  │  │  ├─ DeploymentPodQueryService.java          # 按 Deployment→ReplicaSet→Pod 的 owner UID 聚合分页
│  │  │  ├─ LogService.java                         # 检查 Pod UID、容器与参数，控制日志读取和连接释放
│  │  │  ├─ MetricsService.java                     # 组织指标授权、用量转换及不可用状态处理
│  │  │  ├─ ResourceStreamService.java              # 按权限分发资源变化通知，管理心跳、队列和重同步
│  │  │  ├─ DeploymentMaintenanceService.java       # 预校验维护请求、检查幂等并持久化受理任务
│  │  │  ├─ OperationWorker.java                    # 认领待执行任务，复核权限与目标并提交集群修改
│  │  │  ├─ OperationObserver.java                  # 观察资源是否达到目标，更新结果并恢复未完成任务
│  │  │  └─ AuditService.java                       # 组织审计追加、脱敏及按授权范围查询
│  │  ├─ domain/                                   # 业务概念和规则模型，表达权限、任务状态及审计含义
│  │  │  ├─ permission/                            # 用户范围、操作能力及授权规则模型
│  │  │  ├─ operation/                             # 维护动作、目标、状态机和完成条件模型
│  │  │  └─ audit/                                 # 审计事件、操作者及字段变更模型
│  │  ├─ infrastructure/                           # 基础设施层，落实集群访问、数据库读写和快照存储
│  │  │  ├─ kubernetes/                             # 封装 Kubernetes 客户端，访问 API Server 和 Metrics API
│  │  │  │  ├─ ClusterClientRegistry.java           # 按 clusterId 管理预配置客户端及其连接生命周期
│  │  │  │  ├─ KubernetesResourceAdapter.java       # 封装节点、工作负载、事件等资源的上游查询
│  │  │  │  ├─ KubernetesMaintenanceAdapter.java    # 携带 UID/版本前置条件提交副本、模板标记和镜像修改
│  │  │  │  ├─ KubernetesLogAdapter.java            # 调用 pods/log，执行普通读取或打开 follow 连接
│  │  │  │  ├─ KubernetesMetricsAdapter.java        # 探测并读取 Metrics API，保留原始采样信息
│  │  │  │  ├─ KubernetesWatchRegistry.java         # 复用和管理资源 Watch/Informer，处理断线与重新 List
│  │  │  │  ├─ KubernetesDtoMapper.java             # 将 Kubernetes 原始模型转换为 Dashboard DTO
│  │  │  │  └─ KubernetesErrorTranslator.java       # 将上游权限、冲突、超时等错误转换为脱敏应用错误
│  │  │  ├─ persistence/                           # 封装数据库读写，保存 Dashboard 自身的持久化数据
│  │  │  │  ├─ UserRepository.java                  # 读取和保存用户账户、密码散列及启用状态
│  │  │  │  ├─ GrantRepository.java                 # 读取和保存用户的集群、命名空间及能力授权
│  │  │  │  ├─ OperationRepository.java             # 保存任务、更新执行结果，并支持租约认领与恢复查询
│  │  │  │  ├─ IdempotencyRepository.java           # 持久化幂等键、请求哈希及原任务关联，支持并发去重
│  │  │  │  └─ AuditRepository.java                 # 追加审计记录，执行授权范围过滤及数据库游标分页
│  │  │  └─ snapshot/                              # 保存聚合列表的短期分页快照
│  │  │     └─ PaginationSnapshotStore.java         # 生成和校验绑定用户与查询的令牌，管理快照容量及过期
│  │  └─ support/                                  # 跨模块共用的基础能力，如请求关联和统一时间来源
│  │     ├─ RequestIdFilter.java                    # 为请求设置 requestId，关联响应、运行日志与审计
│  │     └─ ClockProvider.java                      # 统一提供当前时间，便于超时判断及测试时模拟时间
│  ├─ src/main/resources/                          # 后端配置和随应用打包的资源文件
│  │  ├─ application.yml                           # 应用运行配置及外部环境参数引用
│  │  └─ db/migration/                             # 数据库版本迁移脚本，创建或变更表、索引和约束
│  └─ src/test/java/com/example/dashboard/          # 后端测试源码根包
│     ├─ contract/                                 # 验证接口路径、状态码、内容类型和 DTO 与契约一致
│     ├─ security/                                 # 验证登录、CSRF、会话失效和跨范围越权防护
│     ├─ application/                              # 验证业务流程、owner 归属、幂等及任务状态判断
│     └─ integration/                              # 验证真实数据库、Kubernetes API 和客户端组合行为
├─ frontend/                                       # 浏览器端工程，提供资源查看和维护操作界面
│  ├─ package.json                                 # 前端依赖及开发、构建、测试脚本配置
│  ├─ src/                                         # 前端应用源码
│  │  ├─ main.ts                                   # 创建前端应用，注册路由、状态管理和全局组件
│  │  ├─ app/                                      # 应用级路由、整体布局和页面组织
│  │  │  ├─ router.ts                              # 定义页面路由、登录守卫及页面访问条件
│  │  │  └─ layouts/DashboardLayout.vue             # 页面公共布局，包含导航、用户和集群/命名空间切换
│  │  ├─ api/                                      # 对 Dashboard API 的调用封装，供页面和组合函数使用
│  │  │  ├─ generated/                             # 从 OpenAPI 生成的 DTO 与客户端类型
│  │  │  ├─ http.ts                                # 统一请求、CSRF、响应解析、取消请求和错误规范化
│  │  │  ├─ auth.ts                                # 封装获取 CSRF、登录、退出及当前用户查询
│  │  │  ├─ clusters.ts                            # 封装集群、命名空间、概览和有效能力查询
│  │  │  ├─ nodes.ts                               # 封装节点列表与详情查询
│  │  │  ├─ deployments.ts                         # 封装 Deployment 查询、关联 Pod 和三种维护请求
│  │  │  ├─ pods.ts                                # 封装 Pod 列表、详情及普通日志读取
│  │  │  ├─ resources.ts                           # 封装白名单资源的只读 YAML 获取
│  │  │  ├─ events.ts                              # 封装命名空间与对象事件查询
│  │  │  ├─ metrics.ts                             # 封装节点和 Pod 当前指标查询
│  │  │  ├─ operations.ts                          # 封装维护任务状态与执行结果查询
│  │  │  ├─ audit.ts                               # 封装审计筛选、数据库游标分页和详情查询
│  │  │  └─ streams.ts                             # 封装 EventSource 连接、命名事件、错误和关闭操作
│  │  ├─ stores/                                   # 跨页面共享状态，保存当前用户和操作范围
│  │  │  ├─ auth.ts                                # 保存用户、角色、登录状态和当前权限信息
│  │  │  └─ scope.ts                               # 保存选中的集群与命名空间，协调范围切换
│  │  ├─ composables/                              # 可复用的页面交互逻辑，组合 API、状态与组件生命周期
│  │  │  ├─ useCursorList.ts                        # 处理继续令牌分页、筛选变化和分页过期重置
│  │  │  ├─ useNamespaceStream.ts                   # 管理资源订阅、事件合并刷新及切换页面后的连接释放
│  │  │  ├─ usePodLogs.ts                           # 管理容器选择、普通日志、日志跟随和文本缓冲
│  │  │  └─ useOperation.ts                         # 管理任务轮询、完成状态展示及页面离开后的停止
│  │  ├─ views/                                    # 路由对应的完整页面，组合组件及数据调用
│  │  │  ├─ LoginView.vue                           # 用户登录表单及认证错误展示
│  │  │  ├─ OverviewView.vue                        # 集群连接、命名空间统计与可选指标概览
│  │  │  ├─ NodeListView.vue                        # 节点分页列表、状态及容量摘要
│  │  │  ├─ NodeDetailView.vue                      # 节点详情、条件、容量与当前指标
│  │  │  ├─ DeploymentListView.vue                  # Deployment 列表、筛选及发布状态摘要
│  │  │  ├─ DeploymentDetailView.vue                # Deployment 详情、关联 Pod、事件和维护入口
│  │  │  ├─ PodListView.vue                         # Pod 分页列表及节点、阶段和标签筛选
│  │  │  ├─ PodDetailView.vue                       # Pod 与容器状态、事件、指标、日志及 YAML
│  │  │  ├─ OperationDetailView.vue                 # 维护任务进度、结果、不确定状态及错误说明
│  │  │  └─ AuditView.vue                           # 审计记录筛选、分页和变更详情
│  │  ├─ components/                               # 多个页面复用的界面组件
│  │  │  ├─ ResourceTable.vue                       # 资源表格及加载、空数据、错误和分页状态
│  │  │  ├─ ResourceYamlViewer.vue                  # 展示只读资源 YAML 文本
│  │  │  ├─ ContainerLogViewer.vue                  # 展示容器日志，提供选择容器、上一实例和跟随控制
│  │  │  ├─ MetricValue.vue                         # 展示指标数值、单位、不可用或过期状态
│  │  │  ├─ ScaleDialog.vue                         # 填写副本数和原因，确认扩缩容与缩为 0 的操作
│  │  │  ├─ RestartDialog.vue                       # 展示重启目标与发布策略，填写原因并确认
│  │  │  └─ UpdateImageDialog.vue                   # 按普通容器选择新镜像，填写原因并确认变更
│  │  └─ types/                                    # 前端手写的通用类型和页面状态类型
│  │     └─ api.ts                                 # 定义 Envelope<T> 等通用 API 包装类型
│  └─ tests/                                       # 前端组件、交互逻辑和完整用户流程测试
│     ├─ unit/                                     # 验证组件、状态管理、API 封装及组合函数
│     └─ e2e/                                      # 验证登录、排障、维护及权限变化等浏览器流程
└─ deploy/                                         # 应用部署、反向代理和集群权限配置
   ├─ nginx.conf                                   # 静态资源与 API 同源代理，以及 SSE 缓冲和超时配置
   ├─ docker-compose.yml                           # 编排开发环境服务及依赖，凭证通过外部配置注入
   └─ kubernetes/                                  # 将 Dashboard 自身部署进集群的资源清单
      ├─ dashboard.yaml                            # Dashboard 的工作负载、服务及运行配置引用
      └─ rbac/                                     # 后端集群身份所需的 Kubernetes 权限清单
         ├─ reader.yaml                            # 资源查询、日志读取及变化监听所需权限
         └─ maintainer.yaml                        # 扩缩容、重启和镜像更新所需的额外写权限
```

`com.example.dashboard` 是占位包名；实施时统一替换。文件数量可按实际规模合并，但安全、应用流程、上游访问和持久化边界保持明确。

### 2.2 后端主要服务接口

这是应用内接口设计，不是浏览器可调用的 HTTP API。`Actor` 来自可信 Session；`Scope` 包含集群、命名空间；`Target` 额外包含资源类型、名称、UID。

| 接口 / 服务 | 主要方法 | 职责 |
|---|---|---|
| `PermissionEvaluator` | `require(actor, scope, capability)`、`effectiveAccess(actor, scope)` | 检查 Dashboard 授权与上游身份能力 |
| `ClusterClientRegistry` | `getConfiguredClient(clusterId)` | 只返回预配置客户端，统一生命周期与证书校验 |
| `ResourceQueryService` | `listPods`、`getPod`、`listDeployments`、`getDeployment`、`listNodes`、`listEvents` | 授权、参数语义、DTO 返回；不泄露未授权对象 |
| `DeploymentPodQueryService` | `listOwnedPods(actor, target, pageQuery)` | 依 owner UID 链聚合和快照分页 |
| `LogService` | `read(actor, target, logQuery)`、`follow(actor, target, logQuery)` | 容器选择、UID 检查、日志限额、连接释放 |
| `MetricsService` | `nodeMetrics`、`podMetrics` | 指标量纲转换、可用性与采样时间 |
| `ResourceStreamService` | `subscribe(actor, scope, resources)` | 权限过滤、失效通知、心跳和有界队列 |
| `DeploymentMaintenanceService` | `acceptScale`、`acceptRestart`、`acceptImages` | 校验、幂等查重、事务持久化任务与审计，返回 operation |
| `OperationWorker` | `claimAndSubmit(operationId)` | 租约认领、执行前复核、带前置条件提交 |
| `OperationObserver` | `observe(operationId)`、`recoverIncomplete()` | 收敛判断、失败识别、重启恢复、不确定结果核对 |
| `AuditService` | `list(actor, filter)`、`get(actor, recordId)` | 数据库游标、范围授权、脱敏 |

Adapter 负责上游模型，Repository 负责数据库模型，Application Service 负责业务语义。禁止在日志或异常中直接打印完整 kubeconfig、请求凭证、日志正文及 YAML 正文。

## 3. 用户、权限与认证

### 3.1 用户授权模型

建议数据库保存 `user → grant(clusterId, namespaceScope, capabilities)`。namespaceScope 可以是具体命名空间或显式授予的全部命名空间范围；节点权限属于集群范围。

角色只是权限集合模板，后端最终检查 capability 和 scope：

| 模板 | 典型能力 | 备注 |
|---|---|---|
| `VIEWER` | 资源查看、日志、事件、YAML、指标 | YAML 与日志仍可从实际 grant 中单独排除 |
| `OPERATOR` | VIEWER + 扩缩容、重启、镜像更新 | 缩为 0 需额外 `deployment.scaleToZero` |
| `AUDITOR` | `audit.read`、`operation.read` | 不能因此获得日志或资源内容 |
| `ADMIN` | 管理员按范围获准的能力 | 本版不包含用户管理页面，也不能绕过 Kubernetes 权限 |

新增 capability 名称及原文已有名称见第 5 节。角色、用户 ID、范围均由服务端确定，前端请求不能自报角色。

`GET .../access` 返回范围内的**有效能力**。SelfSubjectAccessReview 查询后端使用的 Kubernetes 身份，而不是 Dashboard 登录用户；有效能力必须与应用授权取交集。不能在每个用户之间共享未经 scope 和身份区分的能力缓存。

### 3.2 登录流程

1. 匿名调用 `GET /api/v1/auth/csrf`，保存返回的 `headerName`、`token`。
2. 调用 `POST /api/v1/auth/login`，发送 JSON 用户名密码及 CSRF 请求头。
3. 登录成功更新 Session 标识并设置 Cookie；前端重新获取 CSRF Token。
4. 获取 `auth/me`、集群列表、允许的命名空间及当前范围的 `access`。
5. 写请求携带 CSRF；维护请求另携带 `Idempotency-Key`。
6. 退出调用 `POST /api/v1/auth/logout`；204 后关闭 SSE、清空前端状态。下次登录前重新获取 CSRF。

建议 Cookie 名称固定为 `DASHBOARD_SESSION`，生产启用 `HttpOnly`、`Secure`、`SameSite=Lax`。Session 标识与密码不进入 localStorage。

JSON 登录使用安全过滤器或等效的 Spring Security 认证流程；必须执行会话认证策略、持久化 SecurityContext 并正确处理 CSRF Token 更新。不能只在 Controller 中调用密码校验后把用户 ID 放进普通 Session 就认为完成安全配置。CSRF 端点在匿名阶段允许访问；`permitAll` 不代表登录可以绕过 CSRF。[D1][D2]

浏览器登录失败返回 JSON 401，不返回 HTML 登录跳转。CSRF 失败返回 JSON 403。退出、Session 到期、用户停用或权限撤销时，后端主动终止对应的长连接。

## 4. 通用 HTTP 契约

### 4.1 地址和内容类型

```text
Dashboard API 根：/api/v1
集群前缀 C：/api/v1/clusters/{clusterId}
命名空间前缀 N：/api/v1/clusters/{clusterId}/namespaces/{namespace}
```

`C`、`N` 仅用于本文件表格；OpenAPI 使用完整展开路径。下文全部 API 均由 Java Dashboard 提供，不是直接暴露 Kubernetes API。

| 响应 | Content-Type | 规则 |
|---|---|---|
| JSON | `application/json` | 成功使用 Envelope，失败使用 ErrorEnvelope |
| 普通日志 | `text/plain; charset=utf-8` | 文本原样呈现，不按 HTML 渲染 |
| YAML | `application/yaml` | 只读白名单，独立内容读取权限 |
| 资源与日志流 | `text/event-stream` | SSE 命名事件，保持同源 Cookie |
| 退出 | 无正文，HTTP 204 | 前端不得继续调用 `response.json()` |

所有响应含 `X-Request-ID`。日志、YAML、SSE、认证和包含用户权限的响应建议使用 `Cache-Control: no-store`。请求 ID 可用于用户反馈和审计关联，不包含凭证信息。

### 4.2 成功、失败及分页

```typescript
type Source = "API_SERVER" | "METRICS_API" | "CACHE" | "DATABASE";

interface Envelope<T> {
  requestId: string;
  data: T;
  meta: {
    observedAt: string; // RFC3339
    source: Source;
    stale: boolean;
  };
}

interface ResourcePage<T> {
  items: T[];
  continueToken: string | null;
  listResourceVersion: string | null;
}

interface AuditPage {
  items: AuditRecordDto[];
  nextCursor: string | null; // 数据库游标，不是 Kubernetes continue
}

interface ErrorEnvelope {
  requestId: string;
  error: {
    code: string;
    message: string;
    retryable: boolean;
    details?: Record<string, unknown>; // 仅脱敏、安全的补充字段
  };
}
```

分页列表默认 `limit=50`，范围 `1..200`。读取下一页时保存筛选条件并带回 `continueToken`；不展示虚构的总条数，不使用 `pageNum`。

- 原生资源列表保留上游 continue 和 resourceVersion，前端将其视为不透明字符串。
- 命名空间授权过滤及 Deployment 关联 Pod 列表使用后端快照令牌，绑定用户、scope、筛选和 limit。建议快照 TTL 2 分钟，可配置；过期返回 410。
- 快照生成需完整遍历相关上游列表并过滤，再分页；配置容量上限，超过时返回明确错误，不能返回截断结果冒充完整结果。
- 聚合列表 `listResourceVersion=null`；不能给多个资源集合编造一个统一 Kubernetes 版本。
- 已配置集群目录返回相同的 `ResourcePage` 形状，但不分页，两个令牌字段为 null。
- 修改筛选、切换命名空间、收到重同步通知后，从第一页重新查询。
- 名称模糊搜索、任意排序、任意 `fieldSelector` 均不在 v1 范围。

### 4.3 数据语义

| 数据 | 类型 / 语义 |
|---|---|
| UID、resourceVersion、continueToken | `string`，不转数字，不比较版本大小 |
| generation、observedGeneration | 非负整数；未知 observedGeneration 为 null |
| RFC3339 时间 | API 返回时区信息，前端按用户时区展示 |
| 容量、request、limit | Kubernetes quantity 字符串，如 `cpu="500m"`、`memory="512Mi"` |
| CPU 实际用量 | `cpuUsageMillicores: number`，允许小数 |
| 内存实际用量 | `memoryUsageBytes: integer`，转换显示为 MiB / GiB |
| Ready | `boolean | null`；null 表示未知，不能默认为 true 或 false |
| 未提供的上游状态 | 按契约使用 null、UNKNOWN 或空集合，不虚构健康结果 |

## 5. 供前端调用的全部 API

通用失败响应见第 9 节；各字段、枚举和必填规则以附带 OpenAPI 为准。

### 5.1 认证、集群与权限：9 个操作

| 方法 | 路径 | 输入 | 成功 data / 状态 | 应用权限 |
|---|---|---|---|---|
| GET | `/api/v1/auth/csrf` | 无，允许匿名 | `CsrfDto` / 200 | 无 |
| POST | `/api/v1/auth/login` | `LoginRequest` + CSRF | `CurrentUserDto` / 200，设置 Cookie | 匿名认证流程 |
| POST | `/api/v1/auth/logout` | CSRF | 无正文 / 204 | 已登录 |
| GET | `/api/v1/auth/me` | 无 | `CurrentUserDto` / 200 | 已登录 |
| GET | `/api/v1/clusters` | 无 | `ResourcePage<ClusterSummaryDto>` / 200 | 当前用户可访问集群 |
| GET | `C/status` | 无 | `ClusterStatusDto` / 200 | `cluster.read` |
| GET | `C/overview` | 必填 `namespace` | `OverviewDto` / 200 | `overview.read`，各分区另查资源权限 |
| GET | `C/namespaces` | `limit`、`continueToken` | `ResourcePage<NamespaceDto>` / 200 | 已有集群访问范围 |
| GET | `C/access` | 必填 `namespace` | `NamespaceAccessDto` / 200 | 已有该命名空间访问范围 |

集群连通性探测可在 HTTP 200 中返回 `DISCONNECTED` 或 `AUTH_FAILED`；这是状态查询结果。实际资源请求无法连接集群时使用 503。

### 5.2 节点、Deployment、Pod 和排障：12 个操作

| 方法 | 路径 | 查询参数 | 成功 data / 内容 | 应用权限 |
|---|---|---|---|---|
| GET | `C/nodes` | 分页、`labelSelector` | `ResourcePage<NodeDto>` | `node.read` |
| GET | `C/nodes/{name}` | 无 | `NodeDto` | `node.read` |
| GET | `N/deployments` | 分页、`labelSelector` | `ResourcePage<DeploymentDto>` | `deployment.read` |
| GET | `N/deployments/{name}` | 无 | `DeploymentDto` | `deployment.read` |
| GET | `N/deployments/{name}/pods` | 分页 | `ResourcePage<PodDto>` | `deployment.read` + `pod.read` |
| GET | `N/pods` | 分页、`labelSelector`、`nodeName`、`phase` | `ResourcePage<PodDto>` | `pod.read` |
| GET | `N/pods/{name}` | 无 | `PodDto` | `pod.read` |
| GET | `N/events` | 分页、`involvedObjectUid`、`type` | `ResourcePage<EventDto>` | `event.read` |
| GET | `N/pods/{name}/logs` | 日志参数，见第 7 节 | `text/plain` | `pod.logs.read` |
| GET | `N/pods/{name}/logs/stream` | 流式日志参数 | SSE | `pod.logs.read` |
| GET | `N/resources/{resource}/{name}/yaml` | resource 只允许 pods / deployments | `application/yaml` | 对应资源 read + `resource.yaml.read` |
| GET | `N/stream` | `resources=pods,deployments`，至少一种 | SSE | 逐项检查对应 read |

以上查询成功均为 200。关联 Pod 不能仅依赖标签或名称前缀，需要 `Deployment UID → ReplicaSet owner UID → Pod owner UID`。上游查询失败时返回失败，不能把未知归属当成空列表。

### 5.3 指标：2 个操作

| 方法 | 路径 | 成功 data | 应用权限 |
|---|---|---|---|
| GET | `C/metrics/nodes` | `MetricsDto` / 200 | `node.metrics.read` |
| GET | `N/metrics/pods` | `MetricsDto` / 200 | `pod.metrics.read` |

`MetricsDto.availability`：`AVAILABLE / NOT_INSTALLED / UPSTREAM_FORBIDDEN / UNAVAILABLE`。用户无权返回 403；用户有权而上游指标不可用返回 200、明确原因和空 items。只有确认 API 不存在才使用 `NOT_INSTALLED`。

### 5.4 维护、任务与审计：7 个操作

| 方法 | 路径 | 输入 | 成功 data / 状态 | 应用权限 |
|---|---|---|---|---|
| GET | `N/deployments/{name}/scale` | 无 | `ScaleDto` / 200 | `deployment.read` |
| PUT | `N/deployments/{name}/scale` | `ScaleRequest` | `OperationDto` / 202 | `deployment.scale`；0 另需 `deployment.scaleToZero` |
| POST | `N/deployments/{name}/restart` | `RestartRequest` | `OperationDto` / 202 | `deployment.restart` |
| PUT | `N/deployments/{name}/images` | `ImagesRequest` | `OperationDto` / 202 | `deployment.image.update` |
| GET | `C/operations/{operationId}` | 无 | `OperationDto` / 200 | `operation.read` + 目标 scope |
| GET | `C/audit-records` | 审计筛选与 cursor | `AuditPage` / 200 | `audit.read` + 每条记录 scope |
| GET | `C/audit-records/{recordId}` | 无 | `AuditRecordDto` / 200 | `audit.read` + 记录 scope |

三个维护接口都必须带 `X-CSRF-TOKEN` 和 `Idempotency-Key`。接收前校验失败返回 4xx / 5xx；受理后返回 202、`Location` 和 operation，后续错误由任务状态体现。

新增权限约定：授予任何维护能力时同时授予相同范围的 `operation.read`，便于用户观察提交结果。审计读取独立授予。列表或详情不能通过猜 operationId / recordId 绕过 scope。

审计参数：`cursor`、`limit`、`namespace`、`actorId`、`action`、`status`、`from`、`to`。`from/to` 为 RFC3339，`from <= to`；limit 同样为 1..200。

## 6. 核心 DTO 与页面模型

以下给出前端最需要的字段。附带 OpenAPI 包含全部 DTO 的机器可读定义，避免在两份文件手工维护完整类型。

### 6.1 用户、集群和概览

| DTO | 字段与说明 |
|---|---|
| `CsrfDto` | `headerName: string`，v1 固定 `X-CSRF-TOKEN`；`token: string` |
| `CurrentUserDto` | `id`、`username`、`displayName`、`roles: string[]`、`scopes: AuthorizedScopeDto[]` |
| `AuthorizedScopeDto` | `clusterId`、`namespaces: string[]`、`allNamespaces: boolean`、`capabilities: string[]` |
| `ClusterSummaryDto` | `clusterId`、`displayName`；不返回连接凭证或 kubeconfig |
| `ClusterStatusDto` | `clusterId`、`connectionStatus`、`apiServerVersion: string|null`、`checkedAt`、`lastSuccessAt: string|null`、`features` |
| `NamespaceDto` | `name`、`uid`、`phase`、`labels`、`createdAt` |
| `NamespaceAccessDto` | `clusterId`、`namespace`、`capabilities: string[]`、`constraints.maxReplicas` |
| `OverviewDto` | `namespace`、`podStats`、`deploymentStats`、`nodeStats`；每个分区含 `availability`、`reason`、`observedAt`、`source`、`stale` 和可为空的 `counts` |

分区 `availability` 为 `AVAILABLE / FORBIDDEN / UNAVAILABLE`。某分区无权或失败时 `counts=null`；不能返回 0。节点统计是可选的集群范围数据，不把节点权限默认授予命名空间用户。

Pod 统计包括 `total/ready/pending/running/failed/deleting`，这些计数可能重叠，例如 Running 且正在删除。Deployment 统计包括 `total/available`；这里 available 指 `Available=True`，不表示本次发布已经完成。计数必须遍历全量已授权范围或来自明确标记的缓存，不能统计当前一页冒充全量。

### 6.2 资源 DTO

| DTO | 关键字段 | 页面注意事项 |
|---|---|---|
| `NodeDto` | name、uid、resourceVersion、ready、unschedulable、roles、internalIPs、kubeletVersion、capacity、allocatable、conditions | roles 从角色标签推导；节点 Ready 不代表业务健康 |
| `PodDto` | name、namespace、uid、resourceVersion、phase、ready、deleting、nodeName、podIPs、owners、labels、createdAt、conditions、containers、initContainers | Running 与 Ready 分开展示；按 UID 维护选中对象 |
| `ContainerStatusDto` | name、image、ready、restartCount、state、reason、message、startedAt、termination、lastTermination、resources | state 为 RUNNING / WAITING / TERMINATED / UNKNOWN；普通和初始化容器分开 |
| `DeploymentDto` | name、namespace、uid、resourceVersion、generation、observedGeneration、desiredReplicas、replicas、readyReplicas、updatedReplicas、availableReplicas、paused、strategy、conditions、containers、selector、createdAt | containers 为模板规格，不是 Pod 容器运行状态 |
| `ScaleDto` | uid、resourceVersion、desiredReplicas、actualReplicas、hpaManaged | 扩缩容用 Scale 返回的版本；HPA 无法检查时请求失败 |
| `EventDto` | uid、type、reason、message、count、firstObservedAt、lastObservedAt、involvedObject、reportingComponent | 时间可以为 null，事件不是永久历史 |
| `MetricDto` | name、namespace、sampledAt、window、cpuUsageMillicores、memoryUsageBytes、containers | Node 指标 namespace=null、containers=[] |

`ContainerStatusDto.resources` 和 Deployment 的容器规格均包含 `requests/limits` quantity 字符串映射。`termination` 表示当前已终止实例，`lastTermination` 表示上一终止实例；无对应状态时为 null。缺失容器状态时返回 `state=UNKNOWN`、`ready=null`，不伪造运行状态。

Deployment `strategy` 包含 `type: RollingUpdate|Recreate` 和可选 `maxSurge/maxUnavailable`；百分比和整数都使用字符串显示，避免丢失原始含义。

### 6.3 操作和审计 DTO

```typescript
type OperationStatus =
  | "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED"
  | "TIMED_OUT" | "SUPERSEDED" | "UNKNOWN";

interface OperationDto {
  operationId: string;
  action: "DEPLOYMENT_SCALE" | "DEPLOYMENT_RESTART" | "DEPLOYMENT_IMAGE_UPDATE";
  status: OperationStatus;
  phase: "ACCEPTED" | "REVALIDATING" | "SUBMITTING"
    | "OBSERVING" | "RECONCILING" | "FINISHED";
  target: {
    clusterId: string;
    namespace: string;
    resource: "deployments";
    name: string;
    uid: string;
  };
  targetGeneration: number | null;
  createdAt: string;
  updatedAt: string;
  deadlineAt: string;
  message: string | null;
  error: { code: string; message: string; retryable: boolean } | null;
  result: {
    mode: "SCALE_VERIFIED" | "ROLLOUT_VERIFIED" | "CONFIG_APPLIED";
    containersStartedVerified: boolean;
    warnings: string[];
    changes: ChangeDto[];
  } | null;
}

interface ChangeDto {
  field: string;          // 如 spec.replicas 或 containers.app.image
  before: string | null; // 脱敏后的显示值
  after: string | null;
}
```

`AuditRecordDto` 含 recordId、operationId（允许 null）、actorId、clusterId、namespace、resource、name、uid、action、reason、changes、status、requestId、createdAt。审计 status 在任务状态之外允许 `REJECTED`，用于记录未受理请求；不保存密码、Token、原始 YAML 或日志。

## 7. 前端调用与页面流程

### 7.1 API 模块导出约定

| 模块 | 建议导出函数 |
|---|---|
| `auth.ts` | `getCsrf()`、`login(input)`、`logout()`、`getMe()` |
| `clusters.ts` | `listClusters()`、`getClusterStatus(clusterId)`、`listNamespaces(clusterId, query)`、`getAccess(scope)`、`getOverview(scope)` |
| `nodes.ts` | `listNodes(clusterId, query)`、`getNode(clusterId, name)` |
| `deployments.ts` | `listDeployments(scope, query)`、`getDeployment(target)`、`listDeploymentPods(target, query)`、`getScale(target)`、`scale(target, input, key)`、`restart(target, input, key)`、`updateImages(target, input, key)` |
| `pods.ts` | `listPods(scope, query)`、`getPod(target)`、`getPodLogs(target, query)` |
| `resources.ts` | `getResourceYaml(target)` |
| `events.ts` | `listEvents(scope, query)` |
| `metrics.ts` | `getNodeMetrics(clusterId)`、`getPodMetrics(scope)` |
| `operations.ts` | `getOperation(clusterId, operationId)` |
| `audit.ts` | `listAudit(clusterId, query)`、`getAuditRecord(clusterId, recordId)` |
| `streams.ts` | `subscribeResources(scope, resources, handlers)`、`subscribePodLogs(target, query, handlers)` |

JSON 包装层返回 `Envelope<T>`，保留 meta 供页面显示过期状态；文本接口返回字符串；SSE 返回可关闭的连接句柄。不能把三种内容都强行套进 JSON 拦截器。

### 7.2 基础调用示例

以下是待实现前端封装的使用示例；DTO 从 OpenAPI 生成。路径中的值应逐段 URL 编码，查询用 `URLSearchParams`。

```typescript
import type { CsrfDto, CurrentUserDto, PodPage } from "./generated";
import type { Envelope } from "../types/api";

let csrf: CsrfDto | null = null;

async function readJson<T>(response: Response): Promise<Envelope<T>> {
  const payload = await response.json();
  if (!response.ok) throw payload;
  return payload;
}

export async function getCsrf(): Promise<void> {
  const response = await fetch("/api/v1/auth/csrf", {
    credentials: "same-origin",
    cache: "no-store"
  });
  csrf = (await readJson<CsrfDto>(response)).data;
}

export async function login(username: string, password: string) {
  await getCsrf();
  const response = await fetch("/api/v1/auth/login", {
    method: "POST",
    credentials: "same-origin",
    headers: {
      "Content-Type": "application/json",
      [csrf!.headerName]: csrf!.token
    },
    body: JSON.stringify({ username, password })
  });
  const user = await readJson<CurrentUserDto>(response);
  await getCsrf();
  return user;
}
```

该片段只说明协议；实际封装需处理 AbortSignal、非 JSON 网关错误、网络错误、204、并发获取 CSRF 和统一错误展示。生成器对类型的导出名可能不同，应按选定生成器调整。

```typescript
const scope = {
  clusterId: "main",
  namespace: "default"
};

const base =
  `/api/v1/clusters/${encodeURIComponent(scope.clusterId)}` +
  `/namespaces/${encodeURIComponent(scope.namespace)}`;

const query = new URLSearchParams({
  limit: "50",
  labelSelector: "app=order-service"
});

const response = await fetch(`${base}/pods?${query}`, {
  credentials: "same-origin"
});
const page = await readJson<PodPage>(response);
// 下一页使用 page.data.continueToken，并保持其余查询参数不变。
```

### 7.3 页面到 API 的映射

| 页面 / 功能 | 首次调用 | 后续操作 |
|---|---|---|
| 登录页 | csrf | login → csrf → me |
| 全局导航 | clusters → namespaces → access | scope 切换时关闭旧流、取消旧请求、清空分页 |
| 概览 | status、overview；按权限调用 metrics | 使用 meta 和分区状态显示数据时效 |
| 节点列表 / 详情 | nodes / nodes/{name} | 节点指标按 name 关联 |
| Deployment 列表 | deployments + namespace stream | 失效通知后重新查询当前列表 |
| Deployment 详情 | deployment、关联 pods、events（involvedObjectUid） | YAML、扩缩容、重启、镜像更新 |
| Pod 列表 | pods；筛选 nodeName / phase / labelSelector | 不提供伪全量名称搜索 |
| Pod 详情 | pod、events、pod metrics | 按容器选择日志、previous、follow、YAML |
| 操作详情 | operations/{operationId} | 轮询直到确定结果，UNKNOWN 单独处理 |
| 审计 | audit-records | cursor 下一页、筛选、查看详情 |

请求返回时检查当前页面 scope 和选中 UID；用户已切换页面时丢弃旧响应。列表 key 使用 UID，不能仅用 name。

### 7.4 日志

普通日志示例：

```http
GET /api/v1/clusters/main/namespaces/default/pods/order-service-example/logs?uid=pod-example-uid&container=app&tailLines=200&timestamps=true&previous=false
Accept: text/plain
```

| 参数 | 规则 |
|---|---|
| uid | 必填；与当前 Pod UID 不一致返回 409 |
| container | 多容器必填；只有一个可选日志容器时可默认；普通和初始化容器均可选择 |
| tailLines | 默认 200，范围 1..10000 |
| sinceSeconds / sinceTime | 正整数 / RFC3339，二者互斥 |
| timestamps | 默认 true |
| previous | 普通日志默认 false；只代表当前 Pod 中该容器的上一实例 |
| limitBytes | 普通日志默认且最大 1048576 |

流式接口使用同样的目标和时间参数，不支持 `previous=true` 或 `limitBytes`。普通日志成功读 `response.text()`；失败依 Content-Type 读取 ErrorEnvelope。

日志 UID 检查不是 Kubernetes 原生原子条件。后端连接前检查、打开日志连接后且向前端发送内容前再次检查；同名重建就关闭，不自动跟随新 Pod。

### 7.5 扩缩容、重启及镜像更新

扩缩容必须先读取 Scale，使用 Scale 的 uid/resourceVersion：

```http
PUT /api/v1/clusters/main/namespaces/default/deployments/order-service/scale
Content-Type: application/json
X-CSRF-TOKEN: <csrf-token>
Idempotency-Key: <uuid>
```

```json
{
  "uid": "deployment-example-uid",
  "resourceVersion": "84219",
  "replicas": 3,
  "reason": "调整日常处理容量"
}
```

重启使用 Deployment 详情的 uid/resourceVersion：

```http
POST /api/v1/clusters/main/namespaces/default/deployments/order-service/restart
```

```json
{
  "uid": "deployment-example-uid",
  "resourceVersion": "84219",
  "reason": "排障后重启工作负载"
}
```

更新镜像也使用 Deployment 详情的 uid/resourceVersion：

```http
PUT /api/v1/clusters/main/namespaces/default/deployments/order-service/images
```

```json
{
  "uid": "deployment-example-uid",
  "resourceVersion": "84219",
  "containers": [
    {
      "name": "app",
      "image": "registry.example.com/order-service:v2"
    }
  ],
  "reason": "部署经过验证的新版本"
}
```

三个请求均遵循以下流程：

1. 检查 access，读取最新对象或 Scale，填入 UID 和对应 resourceVersion。
2. 展示目标集群、命名空间、名称及具体变更，要求填写 reason。
3. 每次新的用户确认生成一个幂等键；同一次提交的网络重试保持该键和原始请求不变。
4. 收到 202 保存 operationId；跟随 `Location` 或调用操作查询接口。
5. PENDING / RUNNING 建议每 2 秒查询，后台页降频；页面离开后停止轮询。
6. SUCCEEDED、FAILED、TIMED_OUT、SUPERSEDED 展示结果并刷新资源；UNKNOWN 展示“不确定，正在核对”，降为每 10 秒查询或提供手动核对，不自动重提写请求。

限制：副本必须为整数且不超过 `access.constraints.maxReplicas`；0 需额外权限和明确确认。HPA 管理的 Deployment 禁止手动扩缩容。暂停 Deployment 禁止重启和更新镜像。副本为 0 禁止重启；镜像更新允许，但结果为 `CONFIG_APPLIED`，`containersStartedVerified=false`。

发生 409 后重新获取对象并让用户重新确认；不能自动换成最新版本再次提交。重启按原有 strategy 执行，Recreate 不能标注为滚动更新。

### 7.6 SSE

资源订阅：

```typescript
const stream = new EventSource(`${base}/stream?resources=pods,deployments`);

stream.addEventListener("resync", () => {
  // 取消旧分页，重新查询当前页面。
});
stream.addEventListener("resource.changed", (event) => {
  const change = JSON.parse((event as MessageEvent).data);
  // 建议按 resource + uid 在 300ms 内合并刷新，避免每个事件都请求列表。
});
stream.addEventListener("stream.error", (event) => {
  // 解析 code/message/retryable；认证或授权失败时关闭，其他情况按策略重连。
});
// 页面卸载、scope 切换或退出时调用 stream.close()。
```

| event | data 结构 | 前端行为 |
|---|---|---|
| `resync` | `{resources, reason}` | 重新查询；reason 如 CONNECTED / WATCH_RESET |
| `resource.changed` | `{resource, name, uid, change}` | change 为 ADDED / UPDATED / DELETED；作为缓存失效通知 |
| `log` | `{podUid, container, line, receivedAt}` | 添加文本行，限制页面缓存行数 |
| `end` | `{reason}` | 关闭日志流；不能仅凭 EOF 宣称容器已退出 |
| `stream.error` | `{requestId, code, message, retryable}` | 展示错误并关闭；可重试才按策略重连 |

每次资源连接成功都发送 resync；v1 不支持事件历史重放，不依赖 Last-Event-ID 恢复。Watch 过期后重新 List 并通知重同步。

每 15 秒发送 SSE 注释心跳。每用户最多 5 个日志流、单会话最长 30 分钟；资源流建议每用户最多 3 个，队列建议 256 个事件，这两项是新增可配置限额。

资源流断线可按 1、2、4、8、最高 30 秒退避重连，每次重连重新查询。原生 EventSource.onerror 不提供 HTTP 错误正文；持续失败时关闭连接，并通过 me / access / status 诊断后决定重连。日志流断线后默认停止并提示，用户重新连接时保留原 UID；不保证重连无重复或无缺口。

流开始前发生错误返回普通 HTTP JSON；开始后发送 stream.error 并关闭。缓冲队列满、页面关闭、Session 失效时必须释放上游 Watch 订阅或日志连接。

## 8. 后端维护任务与持久化

### 8.1 受理与执行顺序

```text
检查 Session、CSRF、应用 scope
  → 按 actorId + clusterId + Idempotency-Key 查重
      → 同请求且已有任务：返回原 operation，仍为 202
      → 不同请求：409 IDEMPOTENCY_KEY_REUSED
  → 首次请求：校验参数、上游能力、UID、版本、HPA / 暂停等限制
  → 数据库事务保存 operation + 幂等记录 + ACCEPTED 审计
  → 返回 202 和 Location
  → Worker 认领任务，重新检查用户权限及目标
  → 带 UID / resourceVersion 前置条件写 Kubernetes
  → 保存提交结果，观察控制器与目标状态
  → 更新 operation 与审计
```

**幂等查重在首次请求的版本预校验之前。** 相同请求重试时资源版本可能已被上一次执行改变，此时应返回原任务，而不是错误地产生版本冲突。查重仍须验证当前用户有权访问该任务。

客户端同键并发请求由数据库唯一约束处理；失败者读取已受理的原任务。进程内 Map 不能替代持久化幂等记录。

### 8.2 原子写入与竞态

- Scale 使用 `deployments/scale` 子资源，原子验证 Scale UID 和版本。
- 重启和镜像更新使用 Deployment UID、resourceVersion 前置条件；可用带 `test` 的 JSON Patch，具体方法在选定 Fabric8 版本后以真实 API 测试确认。
- 重启仅设置本次 operationId 对应的 Pod 模板 annotation；不能通过删除全部 Pod 实现。
- 更新镜像只修改明确指定的普通容器；容器名去重、必须存在，镜像符合服务端仓库策略。
- 在写入前复核 HPA；不能检查 HPA 就停止扩缩容。HPA 检查与 Scale 修改不是跨资源事务，观察阶段继续发现副本目标是否被外部控制器修改。
- 禁止使用无版本锁的便捷修改方法后宣称已满足乐观锁要求；Fabric8 文档明确部分 specialized mutative operations 默认不锁版本。[D3]
- 对未知提交结果禁止透明重试写入。记录 operationId、期望变更、提交尝试和可核对的模板标记，读取目标并核对后再决定状态。

### 8.3 完成条件与恢复

| 状态 | 后端判定 |
|---|---|
| PENDING | 任务已持久化，尚未认领 |
| RUNNING | 执行复核、提交或观察 |
| SUCCEEDED | 满足本次操作的完成条件 |
| FAILED | 明确失败，包括执行时冲突、权限撤销、发布失败 |
| TIMED_OUT | Dashboard 观察期限到；集群可能仍继续工作 |
| SUPERSEDED | UID 改变、目标删除或本次目标字段被其他修改替代 |
| UNKNOWN | 不确定提交或结果；核对中，不代表未执行 |

发布成功要求目标 UID 未变、相关 Pod 模板仍有效、`observedGeneration >= targetGeneration`，且 updatedReplicas、replicas、availableReplicas 达到期望。明确的 `ProgressDeadlineExceeded` 等失败不能报告成功。[D4]

缩容成功需要实际副本和可用副本达到期望，缩到 0 还要确认旧副本已退出。相关状态未知时不能判成功。generation 增加但与任务相关的目标字段未变，不应直接判 SUPERSEDED。

镜像更新且期望副本为 0，仅确认配置已经保存，返回 CONFIG_APPLIED；不宣称镜像可拉取或容器可启动。超时默认 10 分钟，不自动回滚。

恢复时先检查数据库中的 PENDING / RUNNING / UNKNOWN。Worker 使用数据库认领租约防止重复执行；租约过期不等于允许重新发送未知写请求。疑似提交过的操作先核对目标和 operation 标记。UNKNOWN 可在核对后转为 RUNNING、SUCCEEDED、FAILED、SUPERSEDED；长期无法核对保持 UNKNOWN 并交由人工处理。

### 8.4 数据表建议

| 表 | 关键列 | 索引 / 约束 |
|---|---|---|
| `dashboard_user` | id、username、password_hash、display_name、enabled、created_at | username 唯一；密码使用单向散列 |
| `dashboard_grant` | id、user_id、cluster_id、namespace_scope、capabilities | 按 user_id、cluster_id 查授权；role 模板展开后保存能力 |
| `dashboard_operation` | id、actor_id、cluster_id、namespace、target_name、target_uid、action、status、phase、request_json、expected_json、target_generation、result_json、error_json、request_id、lease_owner、lease_until、created_at、updated_at、deadline_at | `(status, updated_at)` 恢复扫描；目标 scope 索引 |
| `dashboard_idempotency` | actor_id、cluster_id、key、request_hash、operation_id、created_at、expires_at | `(actor_id, cluster_id, key)` 唯一；至少保留 24 小时 |
| `dashboard_audit_record` | id、operation_id、actor_id、cluster_id、namespace、resource、name、uid、action、reason、changes_json、status、request_id、created_at | `(cluster_id, created_at, id)` 游标索引；scope 过滤必须在数据库查询中完成 |

规范化请求哈希覆盖方法、展开后的完整路径和校验后的请求体；忽略 JSON 键顺序，不忽略容器数组内容。哈希不包含 CSRF、Cookie 或 Authorization。记录 reason 和镜像信息前按组织策略脱敏。

审计建议采用追加记录：受理、失败、成功等状态变化分别新增记录；审计状态因此表示该条记录的事件状态，不是永远同步变化的任务状态。数据库和保留策略必须保证幂等有效期内 operation 可查询。

## 9. 错误码与前端处理

| HTTP | code | 前端处理 |
|---|---|---|
| 400 | INVALID_ARGUMENT、CONTAINER_REQUIRED | 展示字段错误；多容器补选 container |
| 401 | UNAUTHENTICATED | 关闭长连接，清理用户状态，进入登录 |
| 403 | ACCESS_DENIED | 提示当前 scope 无权；不自动扩大权限 |
| 403 | CSRF_INVALID | 刷新会话和 CSRF；写操作由用户确认后重试 |
| 404 | RESOURCE_NOT_FOUND | 资源可能被删除，返回列表或刷新 |
| 409 | RESOURCE_VERSION_CONFLICT、TARGET_UID_CHANGED | 刷新，重新确认目标 |
| 409 | HPA_MANAGED | 禁止手动扩缩容 |
| 409 | WORKLOAD_PAUSED、WORKLOAD_SCALED_TO_ZERO | 展示限制原因 |
| 409 | IDEMPOTENCY_KEY_REUSED | 不同请求不得复用一个键 |
| 410 | CONTINUE_TOKEN_EXPIRED | 清空游标，从第一页重新读 |
| 422 | PREVIOUS_LOG_NOT_AVAILABLE、UNSUPPORTED_RESOURCE | 展示日志 / 资源不可用原因 |
| 429 | RATE_LIMITED、TOO_MANY_STREAMS、SNAPSHOT_LIMIT_EXCEEDED | 按 Retry-After 处理；关闭多余流或缩小查询 |
| 502 | K8S_PERMISSION_MISSING、K8S_UPSTREAM_ERROR | 展示后端身份权限或上游故障 |
| 503 | CLUSTER_UNREACHABLE、CLUSTER_CREDENTIAL_INVALID | 显示集群故障，禁用维护入口 |
| 504 | UPSTREAM_TIMEOUT | 读请求可有限重试；写请求先确认 operation |

`SNAPSHOT_LIMIT_EXCEEDED` 是本文新增的受控容量错误。生产 UI 显示 message 和 requestId，服务端不得在 message/details 中透出堆栈、凭证、原始 Secret 或完整上游错误。

维护请求已有 operation 后，上游错误写入 `OperationDto.error`，查询接口继续返回 200。前端不能把 operation 查询成功理解成维护成功。

如果写请求的 HTTP 响应完全丢失，前端保持原幂等键和原请求重试以获取原 operation；不生成新键重新发起相同维护意图。Dashboard 数据库与 Kubernetes 没有跨系统事务，不承诺 exactly-once。

## 10. Kubernetes 权限与配置

### 10.1 上游权限映射

| 功能 | apiGroup / resource | verbs |
|---|---|---|
| 版本发现 | 非资源 URL `/version` | get |
| 命名空间目录 | core / namespaces | get、list；服务端允许目录模式可避免全量 list |
| 节点 | core / nodes | get、list |
| Pod、实时通知 | core / pods | get、list、watch |
| 日志及 follow | core / pods/log | get；不需要 watch verb |
| 事件 | core / events | get、list |
| Deployment、通知及观察 | apps / deployments | get、list、watch |
| 关联 Pod | apps / replicasets、core / pods | get、list |
| Scale | apps / deployments/scale | get、patch（采用 PATCH 的建议实现） |
| 重启、镜像 | apps / deployments | get、patch |
| HPA 检查 | autoscaling / horizontalpodautoscalers | list |
| 指标 | metrics.k8s.io / nodes、pods | list |
| 自身能力 | authorization.k8s.io / selfsubjectaccessreviews | create |

只读和维护身份分别准备权限清单。命名空间资源优先 Role/RoleBinding，节点、Namespace 目录等集群范围另行授权。集群身份权限与 Dashboard 用户授权必须同时生效。

### 10.2 建议配置项

以下是自定义配置键示例，需要开发 `DashboardProperties` 绑定；不是直接可运行的完整 Spring 配置：

```yaml
dashboard:
  clusters:
    - id: main
      display-name: 主集群
      auth-mode: kubeconfig       # 或 service-account
      kubeconfig-path: ${KUBECONFIG_PATH}
      allowed-namespaces: [default]
  maintenance:
    enabled: false               # P0 或生产首次接入默认关闭
    max-replicas: 100            # 新增建议默认，实际由运维确认
    operation-timeout: 10m
    idempotency-retention: 24h
    restart-annotation-key: dashboard.example.com/restart-operation
    allowed-image-prefixes: []   # 启用镜像更新前配置允许仓库
  pagination:
    snapshot-ttl: 2m
    max-snapshot-items: 10000
  streams:
    heartbeat: 15s
    max-log-streams-per-user: 5
    max-resource-streams-per-user: 3
    max-log-session: 30m
    resource-queue-size: 256
```

`allowed-namespaces` 是服务端可访问目录上限，与用户 grant 取交集；不自动授予目录内所有资源权限。镜像前缀列表为空时拒绝镜像更新；annotation 域名前缀在生产替换成项目控制的域名或内部约定前缀。

集群客户端连接/读取超时和日志流超时分别设置。凭证通过挂载或密钥管理注入，校验 TLS，不把跳过证书校验作为生产默认值。

### 10.3 代理与运行要求

- 前端静态资源与 `/api` 使用同一域名；开发环境通过 Vite 代理后端。
- Nginx 的 SSE location 关闭响应缓冲与缓存，读取超时大于心跳间隔；同步检查前置负载均衡器的空闲超时。
- 日志流、资源流和普通请求使用合理的独立线程 / 连接预算，断线及时释放。
- 用户退出或授权撤销主动关闭长连接；不能让 30 分钟日志会话继续持有已失效授权。
- Watch 只作为变化来源，按已授权 scope 过滤；不要为每个浏览器无限创建一套全量集群 Watch。
- 后端健康检查反映进程自身状态；可选指标不可用不应直接把进程判为不健康。
- 应用运行日志只记录 requestId、operationId、动作、耗时和脱敏错误；数据库迁移和数据库备份纳入部署流程。

## 11. 实施顺序与验收

| 阶段 | 后端工作 | 前端工作 | 交付标准 |
|---|---|---|---|
| 1：骨架与身份 | 工程、Security、用户授权、错误格式、客户端注册表 | 路由、登录、scope、API 包装 | 登录/退出/CSRF 正常，匿名与越权被拒绝 |
| 2：只读资源 | status、namespace、nodes、deployment、pod、分页 | 概览、列表、详情 | UID/版本/分页正确，未知健康状态可见 |
| 3：排障 | owner 聚合、events、logs、yaml | 事件、容器选择、日志、YAML | 多容器、重建 Pod、上一实例日志均正确 |
| 4：实时与指标 | SSE、Watch 恢复、Metrics 降级 | 合并刷新、断线提示、指标状态 | 缺指标不填 0；授权撤销终止流 |
| 5：受控维护 | 幂等、任务、原子校验、收敛、审计、恢复 | 三种确认框、任务页、审计页 | 202 与最终成功区分，冲突不覆盖 |
| 6：集成发布 | 最小 RBAC、限额、生产代理、恢复演练 | 权限组合、桌面/移动布局、长列表 | 测试集群验收后再接生产 |

### 11.1 重点测试

| 类别 | 必验场景 | 合格结果 |
|---|---|---|
| 契约 | 30 个操作的路径、状态、Content-Type、DTO | 与 OpenAPI 一致，204 无正文 |
| 认证 | 登录前 CSRF、登录后更新、退出、Session 到期 | JSON 错误明确，旧流释放 |
| 越权 | 猜 namespace / clusterId / operationId / auditId | 无越权、无未授权列表内容或计数泄漏 |
| 分页 | 多页、改筛选、过期、owner 过滤超过一页 | 无漏项；聚合令牌与用户绑定；过期 410 |
| 日志 | 多容器、initContainer、previous 不存在、同名重建、断线 | 参数可诊断，不能自动换 UID，连接释放 |
| SSE | Watch 过期、慢客户端、权限撤销、代理断线 | resync、受控断开、页面可恢复 |
| 指标 | API 缺失、上游禁止、网络故障、采样过旧 | availability 与时间正确，无伪造 0 |
| 并发写 | 两个请求同版本、UID 重建、执行前被修改 | 原子冲突被识别，不覆盖其他用户修改 |
| 幂等 | 同键同体、同键不同体、并发同键、后端重启 | 原任务可返回，冲突 409，无重复受理 |
| 维护限制 | HPA、暂停、0 副本、仓库策略、权限撤销 | 对应错误，禁止绕过 |
| 发布结果 | ProgressDeadlineExceeded、目标被覆盖、超时、0 副本改镜像 | FAILED / SUPERSEDED / TIMED_OUT / CONFIG_APPLIED 正确 |
| 故障恢复 | 写入前进程退出、上游已应用但响应丢失、观察期间退出 | 不盲目重启，UNKNOWN 可核对，审计不丢失 |

单元测试关注授权、DTO 转换、owner 归属、幂等和状态机；Mock 测试不能证明真实 API 原子前置条件。真实集成测试必须覆盖所选 Fabric8 版本、Scale UID/resourceVersion 和 JSON Patch test 失败的错误映射。

### 11.2 开工前需要确认的事实

1. API Server 与节点版本、集群网络和 CA 信任方式。
2. 集群外运行还是集群内运行，及后端身份的最小 RBAC。
3. 允许命名空间、用户来源、角色授权和维护开关。
4. Metrics API 的存在、权限与采样有效期策略。
5. 数据库地址、备份、审计保留期、镜像仓库策略及 annotation 前缀。
6. 是否由 GitOps 或其他外部控制器管理工作负载；据此配置允许直接维护的范围。
7. 前后端框架与依赖精确版本；实际安装、兼容性和维护状态验证记录。

以上不阻塞按契约搭建工程和前端 mock；会影响真实集群集成及生产开放维护功能。

## 12. 契约维护规则与依据

新增字段优先采用兼容方式；删除字段、修改类型、改变成功状态或错误语义需升级契约。接口变更同时更新 OpenAPI、开发文档、生成类型及相应测试。

本文将原文的 30 个接口全部纳入机器可读契约。P2 路径只是原文方向，本次不把它们混入已承诺的 v1 API。

原文是项目需求依据。新增实现细节核对了以下官方文档；Framework 文档只用于验证技术行为，不等于已经验证本项目的运行环境：

- [D1] Spring Security 6.5 CSRF：登录及退出后重新获取 Token，匿名 CSRF 端点、SPA 注意事项。`https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html`
- [D2] Spring Security 6.5 SecurityContextHolderFilter：持久化 SecurityContext 的要求。`https://docs.spring.io/spring-security/reference/6.5/api/java/org/springframework/security/web/context/SecurityContextHolderFilter.html`
- [D3] Fabric8 FAQ，Optimistic Locking Behavior：部分 specialized mutative operations 默认不锁版本。`https://github.com/fabric8io/kubernetes-client/blob/main/doc/FAQ.md`
- [D4] Kubernetes 官方文档的 `snapshot-final-v1.31`：Deployment 状态及 HPA Scale 子资源。`https://github.com/kubernetes/website/tree/snapshot-final-v1.31/content/en/docs`

附带 OpenAPI 可用于导入支持 OpenAPI 3.0 的接口工具、生成前端 DTO 和编写契约测试。导入成功或结构校验通过都不代表后端已经实现，也不代表真实集群兼容性测试通过。
