# BidCheck 标书检查工作台

BidCheck 是一个本机单用户工作台，覆盖“上传招标文件 → 确认检查项 → 上传投标文件 → 生成可追溯检查结果”的流程。后端基于 Spring Boot，前端作为静态资源随应用发布；支持本地 H2 模式，也支持 MySQL、RabbitMQ 和 MinIO 组成的单虚拟机模式。

本仓库包含应用源码、H2/MySQL 建表 SQL、部署清单和无敏感值的环境变量示例。运行数据库、上传文件、访问密钥、本机缓存、大型构建产物、真实材料回归样例和内部验收记录不在公开仓库中。

## 主要能力

- 处理 PDF、DOCX、UTF-8 TXT/MD、PNG、JPG/JPEG 文件，单次请求上限为 1 GiB。
- 提取、编辑和确认检查项，组合确定性规则、检索与可选语义模型/OCR。
- 保存原文与页码证据，支持人工复核以及 HTML、CSV、JSON 导出。
- 提供任务持久化与恢复、资料版本冻结和 SHA-256 完整性校验。
- 默认使用 H2 文件数据库；可切换到 MySQL、RabbitMQ、MinIO 单虚拟机模式。
- 提供独立自动核验工作台，支持多资料版本和补充材料复核。

检查结果仅用于辅助复核，不是自动定标、评分或法律结论。未读取或未识别也不等于材料缺失。

整体架构、数据流、SQL 设计和当前瓶颈见 [`IMPLEMENTATION.md`](IMPLEMENTATION.md)。

## 运行要求

- Java 17 或更新版本。
- Maven（仓库不包含 Maven Wrapper）。
- 可选：Python，仅用于本地结构化 OCR。
- 可选：Docker 与 Compose v2，仅用于部署 MySQL、RabbitMQ 和 MinIO。
- 可选：有效的 Aspose.Words 许可证。未配置许可时仍保留原生解析路径，试用截断内容不会作为业务证据。

## 本地快速启动

在新的终端中执行：

```powershell
mvn -f backend/pom.xml test
mvn -f backend/pom.xml package
java -Xmx768m -jar backend/target/bidcheck-0.1.0.jar
```

如果公共 Maven 镜像覆盖了 Aspose 仓库，可在命令中加入 `-s build-settings.xml` 使用仓库提供的镜像配置。

默认监听 `127.0.0.1:8787`，数据保存在 `./data`，使用文件型 H2。浏览器访问 <http://127.0.0.1:8787/>，健康检查为 <http://127.0.0.1:8787/api/health>。

Windows 也可以在完成打包后运行 `./Start-BidCheck.ps1`，或双击 `启动BidCheck.cmd`。脚本从 `JAVA_HOME` 或 `PATH` 查找 Java；`-NoBrowser` 不自动打开浏览器，`-LocalOnly` 仅跳过脚本此前保存的 DPAPI 运行配置。

## 核心配置

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `BIDCHECK_ADDRESS` | `127.0.0.1` | HTTP 监听地址 |
| `BIDCHECK_PORT` | `8787` | HTTP 端口 |
| `BIDCHECK_DATA` | `./data` | 数据、原文和缓存目录 |
| `BIDCHECK_DB_PASSWORD` | 空 | 本地 H2 密码 |
| `BIDCHECK_CHECK_CONCURRENCY` | `3` | 检查并发数，建议保持在 1–4 |
| `BIDCHECK_MODEL_URL` | 空 | 可选语义模型地址 |
| `BIDCHECK_MODEL_KEY` | 空 | 可选语义模型密钥 |
| `BIDCHECK_MODEL` | 空 | 可选语义模型名称 |
| `BIDCHECK_RETRIEVAL_ENABLED` | `false` | 是否启用向量检索与重排 |
| `BIDCHECK_OCR_ENABLED` | `false` | 是否启用单页 OCR |
| `BIDCHECK_PDF_OCR_ENABLED` | `false` | 是否启用 PDF OCR 通道 |
| `BIDCHECK_WORD_LICENSE_PATH` | 空 | Aspose.Words 许可证路径 |

语义模型地址、密钥和名称全部配置后才会启用相应检查。完整变量及默认值见 `backend/src/main/resources/application.yml`。

启用模型、OCR、视觉或检索服务可能把所选原文或图像发送给配置的服务商并产生费用。密钥只应通过进程环境或受控的密钥服务提供，不要提交到仓库。

## 可选结构化 OCR

运行 `./Install-StructuredOcr.ps1` 创建隔离的 Python 环境，然后设置：

```powershell
$env:BIDCHECK_OCR_ENABLED = 'true'
$env:BIDCHECK_OCR_KEY = '通过安全方式注入，不要写入仓库'
$env:BIDCHECK_OCR_LAYOUT_ENABLED = 'true'
$env:BIDCHECK_OCR_LAYOUT_ENGINE = 'native-grid'
$env:BIDCHECK_OCR_LAYOUT_PYTHON = 'C:/path/to/isolated/python.exe'
```

`BIDCHECK_OCR_KEY` 也可以由已配置的 `BIDCHECK_MODEL_KEY` 继承；URL 和模型未覆盖时使用 `application.yml` 默认值。普通 OCR 客户端、远端位置 OCR 和本地网格解析都可用时，`native-grid` 才能提供完整结构证据。结构化 OCR 是补充能力，不替代原文保存和人工复核。

## 数据库 SQL

- `backend/src/main/resources/schema.sql`：H2 本地模式建表脚本。
- `backend/src/main/resources/schema-mysql.sql`：MySQL 部署模式建表脚本。

Spring Boot 启动时会按当前 profile 执行对应 SQL。发布前应备份现有数据库，并在目标版本的副本上验证升级；当前仓库未引入 Flyway/Liquibase 版本化迁移。

## 单虚拟机部署

`deploy/compose.yml` 只部署 MySQL 8.0.36、RabbitMQ 3.13-management 和 MinIO；Java 应用单独运行。该方案是单节点部署，不提供高可用。

部署条件：

- Linux 服务器、Docker 和 Compose v2。
- 三个容器的内存上限合计 4352 MiB，每个服务最多 1 CPU；还需为操作系统和 Java 应用预留资源。
- 一个稳定的服务器私网地址。不要把数据库、消息队列或对象存储端口直接暴露到公网。
- 端口：MySQL `13306`、RabbitMQ AMQP `15673`、RabbitMQ 管理页 `15683`、MinIO API `19000`、MinIO 控制台 `19001`。
- 服务器已缓存 `minio/minio:latest`。当前清单使用 `pull_policy: never`，不会自动下载或换版。

服务器端启动：

```bash
cd deploy
cp .env.example .env
# 替换 CHANGE_ME 和服务器私网地址，然后执行 chmod 600 .env
docker image inspect minio/minio:latest --format '{{.Id}}'
docker compose --env-file .env -f compose.yml config --quiet
docker compose --env-file .env -f compose.yml up -d --wait --wait-timeout 300
docker compose --env-file .env -f compose.yml ps
```

应用侧复制 `deploy/application.env.example` 到部署平台的私密配置，替换全部 `CHANGE_ME`，并使用：

- `SPRING_PROFILES_ACTIVE=vm`
- `BIDCHECK_MYSQL_URL/USER/PASSWORD`
- `BIDCHECK_RABBIT_HOST/PORT/USER/PASSWORD/VHOST/ENABLED`
- `BIDCHECK_STORAGE_TYPE=minio`
- `BIDCHECK_STORAGE_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET`

凭据必须与服务器 `.env` 对应。完整操作、健康检查、备份和恢复步骤见 [`deploy/README.md`](deploy/README.md)。

本地 H2 数据不会自动迁移到 MySQL；需要迁移时参考 `scripts/README-migration.md`，先在备份副本上验证。

## 数据与备份

- 本地模式：停止应用后整体备份 `BIDCHECK_DATA`。
- 虚拟机模式：同时备份应用 `BIDCHECK_DATA` 和三个命名卷 `bidcheck-mysql-data`、`bidcheck-rabbitmq-data`、`bidcheck-minio-data`。
- 不要在日常维护中执行 `docker compose down -v`，该参数会删除持久化卷。
- 备份应保存到另一台受控设备，并定期执行恢复演练。

## 仓库结构

- `backend/src/main/java/`：后端业务、文档解析、检查引擎、持久化和 API。
- `backend/src/main/resources/static/`：浏览器工作台。
- `backend/src/main/resources/schema*.sql`：H2 与 MySQL 建表 SQL。
- `backend/src/test/`：不含真实材料的可公开测试。
- `deploy/`：Compose 清单、变量示例和部署说明。
- `scripts/`：索引、迁移、前端验证和可选兼容性工具。

## 安全与边界

- 当前界面面向单用户、本机使用，不提供多租户认证，也不提供高可用；受控远程访问也必须先补同机反向代理、认证与 TLS。
- `.env`、`data/`、`.local/`、日志、上传文件、模型密钥、真实标书及其回归快照均不得提交。
- 默认 HTTP 仅允许回环来源和本机 Host，并校验浏览器 `Origin` 的协议、主机和端口；远程 UI 默认不受支持。若要远程访问，需在同机回环反向代理之外，增加经过审计的 Origin 处理或代码级受信外部 Origin 配置，并补齐认证与 TLS；应用端口不能直接开放到内网或公网。
- 上传文档可能包含个人信息和商业秘密，应配置访问控制、保留期限和备份策略。
- 第三方组件及模型服务受各自许可和服务条款约束；本仓库不包含 Aspose.Words 许可证。

## 许可

本仓库当前未附带项目级开源许可证。未经权利人明确授权，不应假定代码可用于再分发或商业使用。
