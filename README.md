# 待办事项管理（SQLite）

需要 JDK 17 或更高版本。项目附带 Maven Wrapper，无需单独安装 Maven；首次构建需要联网下载 Maven 和依赖。

源码位于 `src/main/java/task/`，测试位于 `src/test/java/task/`，统一使用 `task` 包；应用入口为 `task.Main`。

## 启动

在项目根目录的 PowerShell 中执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\run.ps1
```

脚本先构建，再启动控制台。无论从哪个目录调用，数据库均位于**项目目录下的 `data/tasks.db`**，首次运行自动建表，之后复用现有数据。

应用入口源码为 `src/main/java/task/Main.java`，完整类名为 `task.Main`。启动脚本通过 JAR 清单中的入口运行应用；根目录遗留的 `.class` 文件不参与此启动流程。

## 测试与打包

在项目目录执行：

```powershell
.\mvnw.cmd clean test
if ($LASTEXITCODE -ne 0) { throw 'Tests failed. Packaging stopped.' }
.\mvnw.cmd package
```

干净构建的测试全部通过后再打包；打包阶段也会执行测试，生成包含 SQLite 驱动的 `target/task-console-1.0.0.jar`。在项目根目录的 PowerShell 中直接运行 JAR：

```powershell
java --enable-native-access=ALL-UNNAMED -jar .\target\task-console-1.0.0.jar
```

直接运行 JAR 而未指定 `task.db` 时，默认使用当前工作目录下的 `data/tasks.db`。

## 数据与并发

- `Task.create` 创建不可变的未保存任务，ID 为 0。调用 `TaskService.addTask` 后，使用返回的新对象获得正式 ID。
- 主键使用 `INTEGER PRIMARY KEY AUTOINCREMENT`。同一数据库内，重启以及删除已提交记录都不会复用其 ID。编号允许跳号；删除整个数据库会重新开始编号。
- 历史记录通过独立入口还原，保留 ID、创建时间和状态，截止时间已过也能读取。
- `TaskService → TaskRepository → SqliteTaskRepository`：数据库是唯一数据源，服务不再维护内存列表。
- 每次操作使用独立 JDBC 连接，通过 try-with-resources 关闭资源。使用参数化 SQL，锁等待最多 5 秒，失败转为业务可处理的异常。
- 当前写入均为单条 SQL，使用 SQLite 隐式事务；插入通过 `RETURNING` 获取本条记录，完成操作使用原子 `UPDATE`。后续多步业务必须在同一个连接的显式事务中提交或回滚。
- SQLite 同时只有一个写入者；当前适合单机任务管理。没有通过共享一个 JDBC 连接或 Java 实例锁协调数据库访问。
- 日期按 ISO 本地时间文本保存，与原有 `LocalDateTime` 模型一致。
- 测试使用独立临时数据库，不向正式数据库写入示例任务。测试覆盖跨 Java 进程重启、删除后 ID、并发写入、锁冲突、过期任务和错误处理。

关闭所有程序实例后，可复制 `data/tasks.db` 进行备份。原版数据仅存在进程内存中，本项目没有可自动迁移的旧数据文件。

驱动使用 [Xerial SQLite JDBC](https://github.com/xerial/sqlite-jdbc)。ID 语义参见 [SQLite AUTOINCREMENT](https://www.sqlite.org/autoinc.html)。
