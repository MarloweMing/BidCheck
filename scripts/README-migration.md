# 本地数据迁移

先停止旧、新应用，备份 H2 的 `.mv.db` 和 `data/files`。工具只读备份，目标 MySQL 的 `qc_record`、`qc_task` 必须为空。先使用 `backend/src/main/resources/schema-mysql.sql` 初始化完整目标 schema；迁移工具只复制 `qc_record`、`qc_task`，不会创建或修改 schema。

需要 JDK 8、已打包的应用 JAR，以及通过当前进程环境注入的变量：`BIDCHECK_MYSQL_URL`、`BIDCHECK_MYSQL_USER`、`BIDCHECK_MYSQL_PASSWORD`、`BIDCHECK_STORAGE_ENDPOINT`、`BIDCHECK_STORAGE_ACCESS_KEY`、`BIDCHECK_STORAGE_SECRET_KEY`。不要把密钥写进命令参数、脚本或版本库。

在项目根目录提取运行依赖，再使用 JDK 8 先编译、后运行，无需启动 Spring：

```powershell
$migrationJar = (Resolve-Path 'backend/target/bidcheck-0.1.0.jar').Path
$migrationLibs = Join-Path (Get-Location) '.local/migration-runtime'
New-Item -ItemType Directory -Force -Path $migrationLibs | Out-Null
Push-Location $migrationLibs
jar xf $migrationJar BOOT-INF/lib
Pop-Location
$migrationClasses = Join-Path $migrationLibs 'classes'
New-Item -ItemType Directory -Force -Path $migrationClasses | Out-Null
javac -encoding UTF-8 -source 8 -target 8 -cp "$migrationLibs/BOOT-INF/lib/*" -d $migrationClasses scripts/MigrateLocalData.java
if ($LASTEXITCODE -ne 0) { throw 'Migration tool compilation failed.' }
java -cp "$migrationClasses;$migrationLibs/BOOT-INF/lib/*" MigrateLocalData 'C:/backup/bidcheck' 'C:/backup/files'
```

Linux 下同样先在新目录执行 `jar xf /absolute/path/bidcheck-0.1.0.jar BOOT-INF/lib`，再运行：

```bash
mkdir -p /absolute/path/migration-runtime/classes
javac -encoding UTF-8 -source 8 -target 8 -cp '/absolute/path/migration-runtime/BOOT-INF/lib/*' -d /absolute/path/migration-runtime/classes scripts/MigrateLocalData.java &&
java -cp '/absolute/path/migration-runtime/classes:/absolute/path/migration-runtime/BOOT-INF/lib/*' MigrateLocalData /backup/bidcheck /backup/files
```

第一个参数是数据库基本路径，例如 `C:/backup/bidcheck`，不要带 `.mv.db`。源 H2 使用原应用默认的 `sa` / 空密码，以 `IFEXISTS` 和只读模式打开。旧任务表没有租约列也可迁移：仅复制固定两张表的共同列。

工具校验已登记 `document` / `upload` 的 UUID、原件大小和已有 SHA-256；同一 UUID 仅上传一次。对象仅写入 `bidcheck/documents/<UUID>/original.bin`，上传后通过实际下载重新计算 SHA-256。若远端已存在同 UUID，仅在内容完全相同时复用，否则拒绝覆盖。

所有原件验证成功且两表源、目标行数一致后才提交 MySQL 事务。失败会尝试回滚，不删除已上传对象；提交时连接中断应先检查目标行数，不能直接重跑覆盖。成功输出 `MIGRATION SUCCESS sourceCount=... destCount=...`；保留备份，改用新的空本地缓存目录启动应用，再核对项目、任务和原件读取。
