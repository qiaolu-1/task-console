# 待办事项管理（SQLite）

需要 JDK 25（最新 LTS）或更高版本。项目附带 Maven Wrapper，无需单独安装 Maven；首次构建需要联网下载 Maven 和依赖。

源码位于 `src/main/java/task/`，按职责划分为 `model`（任务模型）、`service`（业务服务）、`repository`（存储接口与 SQLite 实现）、`ui`（控制台交互）、`exception`（存储异常）、`validation`（校验）子包；应用入口仍为 `task.Main`。测试位于 `src/test/java/task/`。

## 启动

`Main` 创建并组装 Repository、Service 和 UI，调用 `TaskConsoleUI.start()` 启动交互。UI 通过构造器接收 Service，负责菜单循环、输入输出、操作流程和退出时关闭输入资源；数据库初始化失败仍由入口报错并以状态码 1 退出。

UI 只收集输入、解析日期格式并展示结果，新增任务时将字段传给 `TaskService.addTask(content, creator, method, endTime)`，由 Service 创建并保存 `Task`。逐项输入通过 Service 校验，保存时再次检查业务规则；`TaskValidator` 专注业务校验，不再处理控制台日期格式。

调用链为 **UI → Service → Repository → SQLite**。UI 修改、删除前调用 `Service.requireTask`，由服务判断任务是否存在并返回任务或抛出 `TaskNotFoundException`；UI 只展示错误或继续收集输入、确认。Service 校验 ID，并根据数据库中的原截止时间校验修改；模型的 `withUpdates` 校验字段并保留 ID、创建时间和提交方式，`requireNew` 统一限制仅新增未保存且未完成的任务。Repository 负责 SQL、字段映射和存储异常，新增时复用模型的防护，不自行定义业务规则。所有已保存任务（包括已完成、已过期任务）均可修改、删除；更新、删除、完成以 SQL 影响行数返回结果，任务在交互期间被删除时不会报告成功。

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

## 功能与菜单

主菜单：`1. 添加任务`、`2. 查看任务`、`3. 修改任务`、`4. 完成任务`、`5. 删除任务`、`0. 退出`。

查看任务子菜单：`1. 全部`、`2. 未完成`、`3. 已完成`、`4. 按 ID 查找`、`0. 返回主菜单`。列表按 ID 升序显示任务详情，无匹配项时提示空列表。

修改任务时，输入 ID 后逐项修改内容、创建者、截止时间和完成状态。直接回车保留该字段原值；仅输入空格不算保留，内容和创建者不能为空。日期格式为 `yyyy-MM-dd HH:mm`，错误日期会要求重输；完成状态仅接受 `true` 或 `false`，允许将已完成任务改回未完成。输入结束时取消尚未提交的修改。

新截止时间必须晚于当前时间，但可以保留原本已过期的截止时间。ID、创建时间、提交方式保持不变。服务层先查询并校验，再由 Repository 用一条参数化 `UPDATE` 保存四个字段，不需要迁移表结构。多个程序同时编辑同一任务时，最后成功保存的四个字段会覆盖先前修改；目前未加入版本冲突检测。

## 数据与并发

- 菜单 `5. 删除任务` 支持按 ID 删除单条任务（包括已完成任务）。先显示任务详情，再输入 `y`（不区分大小写）确认；回车或其他输入均取消。删除后无法恢复，不存在的 ID 会提示未找到。

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
