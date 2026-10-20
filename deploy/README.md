# BidCheck：本机应用 + 虚拟机中间件

`compose.yml` 仅部署 BidCheck 专用 MySQL、RabbitMQ 和 MinIO。Java 应用可在另一台主机运行，通过局域网连接 `SERVER_PRIVATE_IP`（请替换为服务器实际私网地址）。这是单虚拟机部署，提供持久化与进程重启能力，不提供节点冗余或高可用。

适用目标：CentOS 7、内核 3.10、Docker 26、Compose 2.27、约 7.6 GB 内存。首次启动的镜像兼容性和实际健康状态，以该虚拟机上的验收结果为准。

## 独立资源与服务地址

| 服务 | 镜像 | 容器 | 本机应用访问地址 | 内存上限 |
| --- | --- | --- | --- | --- |
| MySQL | `mysql:8.0.36` | `bidcheck-mysql` | `SERVER_PRIVATE_IP:13306`，数据库 `bidcheck` | 2560 MiB |
| RabbitMQ | `rabbitmq:3.13-management` | `bidcheck-rabbitmq` | AMQP `SERVER_PRIVATE_IP:15673`，虚拟主机 `/bidcheck` | 768 MiB |
| MinIO | 服务器预先缓存的 `minio/minio:latest` | `bidcheck-minio` | S3 API `http://SERVER_PRIVATE_IP:19000`，存储桶 `bidcheck` | 1024 MiB |

RabbitMQ 管理页面为 `http://SERVER_PRIVATE_IP:15683`，MinIO 控制台为 `http://SERVER_PRIVATE_IP:19001`。应用入口默认为 [127.0.0.1:8787](http://127.0.0.1:8787)。MinIO 的应用端点使用 `19000`，不要填控制台端口 `19001`；RabbitMQ 的应用端口是 `15673`，不要填管理端口 `15683`。

三个容器的内存上限合计 4352 MiB，每个容器最多使用 1 个 CPU 的计算配额；应用的 Java 内存在本机计算。资源上限不是预先占用量。网络为 `bidcheck-net`，数据卷为 `bidcheck-mysql-data`、`bidcheck-rabbitmq-data`、`bidcheck-minio-data`。

服务器上的其他容器、网络和数据卷不在本配置的管理范围内。本配置不会主动连接、升级或重启其他项目；Redis、Milvus 不是当前必需依赖，因此未部署。

Compose 对三个服务都配置了健康检查和 `restart: unless-stopped`。该策略在进程退出时处理重启；仅变成 `unhealthy` 不等于会自动重启。详见 [Compose 服务属性](https://docs.docker.com/reference/compose-file/services/)。

## 1. 在虚拟机准备独立目录和凭据

将本目录的 `compose.yml` 放到虚拟机的独立目录，例如 `/opt/bidcheck`。以下 Linux 命令均在该目录执行。不要在已有项目的 Compose 目录运行。

仓库同时提供 `.env.example` 作为中间件变量清单，以及 `application.env.example` 作为 Java 应用变量清单。可以复制后逐项替换 `CHANGE_ME`；生产环境建议直接使用下面的命令分别生成随机密码。示例文件只说明变量，不应填写真实凭据后提交。

在服务器上创建 `.env`，不要将真实凭据提交代码、粘贴到聊天或写入 README。下面的命令仅在 `.env` 不存在时创建文件，为四组密码分别生成 32 字节随机值；已存在时保留原文件。

```bash
if [ -e .env ]; then
  printf '%s\n' '.env 已存在，请沿用当前凭据，未覆盖。'
else
  (
    set -eu
    umask 077
    {
      printf '%s\n' 'BIDCHECK_BIND_IP=SERVER_PRIVATE_IP'
      printf '%s\n' 'BIDCHECK_MYSQL_USER=bidcheck_mysql'
      printf 'MYSQL_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 32)"
      printf 'BIDCHECK_MYSQL_PASSWORD=%s\n' "$(openssl rand -hex 32)"
      printf '%s\n' 'BIDCHECK_RABBIT_USER=bidcheck_rabbit'
      printf 'BIDCHECK_RABBIT_PASSWORD=%s\n' "$(openssl rand -hex 32)"
      printf '%s\n' 'BIDCHECK_RABBIT_VHOST=/bidcheck'
      printf '%s\n' 'BIDCHECK_MINIO_ROOT_USER=bidcheck_minio'
      printf 'BIDCHECK_MINIO_ROOT_PASSWORD=%s\n' "$(openssl rand -hex 32)"
    } > .env
  )
fi
chmod 600 .env
```

用户名可在首次初始化前调整为各服务独立名称。MySQL 应用用户不能填 `root`。所有必需凭据在 Compose 中使用 `${变量名:?说明}` 校验，缺失时直接报错；`.env` 的解析规则见 [Docker 环境变量说明](https://docs.docker.com/compose/how-tos/environment-variables/variable-interpolation/)。

MySQL 的 `MYSQL_USER`、`MYSQL_PASSWORD`、`MYSQL_DATABASE` 等初始化变量只对空数据目录生效。后续修改 `.env` 不能代替数据库内的账号密码变更；已有 RabbitMQ 账号也应通过其管理工具变更，再同步应用配置。MySQL 行为以 [官方镜像说明](https://hub.docker.com/_/mysql) 为准。

## 2. 启动与检查

```bash
docker compose version
docker image inspect minio/minio:latest --format '{{.Id}}'
docker compose --env-file .env -f compose.yml config --quiet
docker compose --env-file .env -f compose.yml up -d --wait --wait-timeout 300
docker compose --env-file .env -f compose.yml ps
docker stats --no-stream bidcheck-mysql bidcheck-rabbitmq bidcheck-minio
```

MinIO 设置了 `pull_policy: never`，仅使用当前虚拟机缓存镜像；缓存缺失时会停止创建，不会自动换版本。保留上面查到的镜像 ID，以便备份和恢复时核对。不要在这次部署中对 MinIO 执行 `pull`。MySQL 和 RabbitMQ 仅在本机缺少指定镜像时拉取。[Compose 镜像拉取策略](https://docs.docker.com/reference/compose-file/services/#pull_policy)

`up --wait` 会等待服务运行且健康；超时应查看状态和日志，而不是反复创建新数据卷。[启动等待参数](https://docs.docker.com/reference/cli/docker/compose/up/)

```bash
docker compose --env-file .env -f compose.yml logs --tail 100 mysql rabbitmq minio
docker inspect --format '{{.Name}} {{.State.Health.Status}}' bidcheck-mysql bidcheck-rabbitmq bidcheck-minio
```

健康检查内容分别是：MySQL 应用用户在 `bidcheck` 库执行 `SELECT 1`；RabbitMQ 检查应用运行状态与本地资源告警；MinIO 检查 `/minio/health/cluster` 的可写状态。[RabbitMQ 检查说明](https://www.rabbitmq.com/docs/3.13/monitoring#health-checks)、[MinIO 健康接口](https://github.com/minio/minio/blob/master/docs/metrics/healthcheck/README.md)

MySQL 使用 UTF-8、512 MiB 缓冲池、128 MiB redo 容量和最多 60 个连接。参数通过 `mysqld` 启动选项传入，未覆盖镜像入口脚本。[MySQL 启动配置](https://hub.docker.com/_/mysql)、[MySQL 8.0 redo 配置](https://dev.mysql.com/doc/refman/8.0/en/innodb-redo-log.html)

RabbitMQ 使用 384 MiB 的绝对内存告警线，磁盘可用空间低于 1 GiB 时会告警；这与容器内存上限是两个不同的控制。绝对阈值避免旧内核或 cgroup 环境对总内存识别产生偏差。[RabbitMQ 容器内存阈值](https://www.rabbitmq.com/docs/3.13/memory#absolute-memory-limit)

发布端口仅绑定 `.env` 中的 `BIDCHECK_BIND_IP`。应用主机无法访问时，先检查路由和这些端口的访问策略；仅为需要使用 BidCheck 的局域网来源开放，不要关闭防火墙。

## 3. 本机应用连接虚拟机

先停止已经运行的本机 BidCheck，再在项目根目录打开 PowerShell。应用必须使用包含 MySQL、RabbitMQ、MinIO 支持的构建；不能用早期仅支持本地存储的旧 JAR。

在当前 PowerShell 会话设置以下变量。密码输入使用隐藏输入；填入服务器 `.env` 中对应的真实值，不会显示在终端输出中。

```powershell
$env:SPRING_PROFILES_ACTIVE = 'vm'
$env:BIDCHECK_ADDRESS = '127.0.0.1'
$env:BIDCHECK_PORT = '8787'
$env:BIDCHECK_DATA = '.local/vm-data'
$env:BIDCHECK_MYSQL_URL = 'jdbc:mysql://SERVER_PRIVATE_IP:13306/bidcheck?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=REQUIRED'
$env:BIDCHECK_MYSQL_USER = 'bidcheck_mysql'
$env:BIDCHECK_MYSQL_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '输入 BIDCHECK_MYSQL_PASSWORD' -AsSecureString)).Password
$env:BIDCHECK_RABBIT_HOST = 'SERVER_PRIVATE_IP'
$env:BIDCHECK_RABBIT_PORT = '15673'
$env:BIDCHECK_RABBIT_USER = 'bidcheck_rabbit'
$env:BIDCHECK_RABBIT_PASSWORD = [System.Net.NetworkCredential]::new('', (Read-Host '输入 BIDCHECK_RABBIT_PASSWORD' -AsSecureString)).Password
$env:BIDCHECK_RABBIT_VHOST = '/bidcheck'
$env:BIDCHECK_RABBIT_ENABLED = 'true'
$env:BIDCHECK_STORAGE_TYPE = 'minio'
$env:BIDCHECK_STORAGE_ENDPOINT = 'http://SERVER_PRIVATE_IP:19000'
$env:BIDCHECK_STORAGE_ACCESS_KEY = 'bidcheck_minio'
$env:BIDCHECK_STORAGE_SECRET_KEY = [System.Net.NetworkCredential]::new('', (Read-Host '输入 BIDCHECK_MINIO_ROOT_PASSWORD' -AsSecureString)).Password
$env:BIDCHECK_STORAGE_BUCKET = 'bidcheck'
java -Xmx768m -jar backend/target/bidcheck-0.1.0.jar
```

如果服务器用户名或 RabbitMQ 虚拟主机采用了其他值，应一并修改本机配置。当前 MinIO 实例仅服务 BidCheck；应用首次需要存储时创建专用 `bidcheck` 桶。本机保留原文缓存和待同步文件，因此 `.local/vm-data` 仍需保留并备份。启用 `vm` 配置后使用的是独立 MySQL 数据库，不会自动迁移原 H2 项目。

启动后打开 [本机工作台](http://127.0.0.1:8787)，验证上传、检查任务和原文下载。`/api/health` 表示应用可响应；三个中间件的可用性还应结合各自健康状态与一次真实上传/检查确认。

## 4. 停止、恢复与备份

只停止本项目中间件：

```bash
docker compose --env-file .env -f compose.yml stop
```

恢复启动并检查健康：

```bash
docker compose --env-file .env -f compose.yml up -d --wait --wait-timeout 300
```

**日常维护禁止使用 `docker compose down -v`。** `-v` 会删除本配置声明的数据卷，其中包含数据库、消息状态与文件。普通 `stop` 保留容器和数据，普通 `down` 保留命名数据卷。[Docker 删除范围说明](https://docs.docker.com/reference/cli/docker/compose/down/)

下面是适合单机环境的停机备份。先停止本机 BidCheck，等待正在处理的工作结束；同时备份本机的 `BIDCHECK_DATA` 目录。然后在虚拟机的部署目录以具有读取 Docker 数据权限的账号运行：

```bash
(
  set -eu
  umask 077
  backup_dir="/var/backups/bidcheck/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$backup_dir"
  docker compose --env-file .env -f compose.yml stop
  for volume in bidcheck-mysql-data bidcheck-rabbitmq-data bidcheck-minio-data; do
    mountpoint=$(docker volume inspect "$volume" --format '{{.Mountpoint}}')
    test -d "$mountpoint"
    tar --numeric-owner -C "$mountpoint" -czf "$backup_dir/$volume.tar.gz" .
  done
  cp compose.yml "$backup_dir/compose.yml"
  cp .env "$backup_dir/.env"
  docker image inspect mysql:8.0.36 rabbitmq:3.13-management minio/minio:latest \
    --format '{{.Id}} {{json .RepoTags}} {{json .RepoDigests}}' > "$backup_dir/images.txt"
  docker compose --env-file .env -f compose.yml up -d --wait --wait-timeout 300
  printf '备份完成：%s\n' "$backup_dir"
)
```

该备份包含 `.env` 与持久化账号信息，按敏感数据保存，并复制到另一台受控设备；仅放在同一虚拟机磁盘上无法应对磁盘丢失。备份失败时上述命令会停止，先确认错误再恢复本项目服务。备份期间现有其他项目的容器保持运行。

恢复时先在独立恢复环境验证：使用备份记录的镜像版本，保持 RabbitMQ 主机名 `bidcheck-rabbitmq`，向空的专用数据卷恢复对应归档及文件所有权，再恢复匹配的 `.env` 和本机 `BIDCHECK_DATA`。不要在运行中的数据目录上覆盖文件。检查三项健康状态、项目数量和一份原文下载后，再切换本机应用连接。
