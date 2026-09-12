package task;

import task.exception.TaskStorageException;
import task.model.Task;
import task.repository.SqliteTaskRepository;
import task.service.TaskService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SqliteTaskRepositoryTest {
    @TempDir Path directory;

    private Path database() { return directory.resolve("tasks.db"); }
    private Task draft(String content) {
        return Task.create(content, "测试用户", "邮件", LocalDateTime.now().plusDays(1));
    }

    @Test void persistsFieldsAndCompletionAcrossRepositoryInstances() {
        TaskService first = new TaskService(new SqliteTaskRepository(database()));
        Task draft = draft("中文 '); DROP TABLE tasks; --");
        assertEquals(0, draft.getId());
        Task saved = first.addTask(draft);
        assertTrue(saved.getId() > 0);
        assertEquals(0, draft.getId());
        assertTrue(first.completeTask(saved.getId()));
        assertTrue(first.completeTask(saved.getId()));
        TaskService reopened = new TaskService(new SqliteTaskRepository(database()));
        Task loaded = reopened.findTaskById(saved.getId());
        assertEquals(draft.getContent(), loaded.getContent());
        assertEquals(draft.getCreator(), loaded.getCreator());
        assertEquals(draft.getMethod(), loaded.getMethod());
        assertEquals(draft.getStartTime(), loaded.getStartTime());
        assertEquals(draft.getEndTime(), loaded.getEndTime());
        assertTrue(loaded.isCompleted());
        assertFalse(saved.isCompleted());
        assertTrue(reopened.getIncompleteTasks().isEmpty());
        assertNull(reopened.findTaskById(Long.MAX_VALUE));
        assertFalse(reopened.completeTask(Long.MAX_VALUE));
    }

    @Test void deletesOnlySelectedTaskAndPersistsDeletion() {
        TaskService service = new TaskService(new SqliteTaskRepository(database()));
        Task first = service.addTask(draft("first"));
        Task second = service.addTask(draft("second"));
        assertTrue(service.deleteTask(first.getId()));
        assertFalse(service.deleteTask(first.getId()));
        assertFalse(service.deleteTask(Long.MAX_VALUE));
        TaskService reopened = new TaskService(new SqliteTaskRepository(database()));
        assertNull(reopened.findTaskById(first.getId()));
        assertEquals(second.getId(), reopened.getIncompleteTasks().getFirst().getId());
        assertEquals(1, reopened.getIncompleteTasks().size());
        assertTrue(reopened.completeTask(second.getId()));
        assertTrue(reopened.deleteTask(second.getId()));
        assertNull(service.findTaskById(second.getId()));
    }

    @Test void consoleRequiresExplicitConfirmationBeforeDeleting() throws Exception {
        TaskService service = new TaskService(new SqliteTaskRepository(database()));
        Task saved = service.addTask(draft("确认删除的任务"));
        for (String answer : new String[] {"", "n", "yes", "随便输入"}) {
            String output = runConsole("5\n" + saved.getId() + "\n" + answer + "\n0\n", "cancel.log");
            assertTrue(output.contains("任务内容: 确认删除的任务"), output);
            assertTrue(output.contains("已取消删除"), output);
            assertNotNull(service.findTaskById(saved.getId()));
        }
        String eof = runConsole("5\n" + saved.getId() + "\n", "eof.log");
        assertTrue(eof.contains("已取消删除"), eof);
        assertNotNull(service.findTaskById(saved.getId()));
        String deleted = runConsole("5\n0\nabc\n" + saved.getId() + "\n Y \n0\n", "delete.log");
        assertTrue(deleted.contains("ID必须是正整数"), deleted);
        assertTrue(deleted.contains("任务已删除"), deleted);
        assertNull(service.findTaskById(saved.getId()));
        String missing = runConsole("5\n" + saved.getId() + "\n0\n", "missing.log");
        assertTrue(missing.contains("未找到该任务"), missing);
        assertFalse(missing.contains("确认删除该任务"), missing);
    }

    @Test void neverReusesDeletedCommittedIdsEvenAfterDeletingAllTasks() throws Exception {
        SqliteTaskRepository repository = new SqliteTaskRepository(database());
        long first = repository.add(draft("one")).getId();
        long highest = repository.add(draft("two")).getId();
        assertTrue(repository.delete(first));
        assertTrue(repository.delete(highest));
        long next = new SqliteTaskRepository(database()).add(draft("three")).getId();
        assertTrue(next > highest);
    }

    @Test void loadsExpiredHistoryWithoutChangingIdentityOrTimes() throws Exception {
        SqliteTaskRepository repository = new SqliteTaskRepository(database());
        Task saved = repository.add(draft("expired"));
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 12, 0);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.prepareStatement("UPDATE tasks SET end_time = ? WHERE id = ?")) {
            statement.setString(1, past.toString());
            statement.setLong(2, saved.getId());
            statement.executeUpdate();
        }
        Task loaded = new SqliteTaskRepository(database()).findById(saved.getId());
        assertEquals(past, loaded.getEndTime());
        assertEquals(saved.getStartTime(), loaded.getStartTime());
        assertEquals(saved.getId(), loaded.getId());
        assertTrue(repository.complete(saved.getId()));
    }

    @Test void returnsImmutableSnapshotsAndRejectsResavingPersistedTasks() {
        SqliteTaskRepository repository = new SqliteTaskRepository(database());
        Task saved = repository.add(draft("snapshot"));
        var snapshot = repository.findIncomplete();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertThrows(IllegalArgumentException.class, () -> repository.add(saved));
        repository.complete(saved.getId());
        assertFalse(snapshot.get(0).isCompleted());
        assertTrue(repository.findIncomplete().isEmpty());
    }

    @Test void concurrentIndependentRepositoriesPreserveIdsAndUpdates() throws Exception {
        SqliteTaskRepository first = new SqliteTaskRepository(database());
        SqliteTaskRepository second = new SqliteTaskRepository(database());
        var executor = Executors.newFixedThreadPool(4);
        try {
            var jobs = new ArrayList<Callable<Long>>();
            for (int i = 0; i < 40; i++) {
                int number = i;
                jobs.add(() -> {
                    SqliteTaskRepository repository = number % 2 == 0 ? first : second;
                    Task task = repository.add(draft("parallel " + number));
                    assertTrue(repository.complete(task.getId()));
                    assertTrue(repository.findById(task.getId()).isCompleted());
                    return task.getId();
                });
            }
            var ids = new HashSet<Long>();
            for (var result : executor.invokeAll(jobs, 30, TimeUnit.SECONDS)) {
                assertTrue(ids.add(result.get()));
            }
            assertEquals(40, ids.size());
            assertTrue(first.findIncomplete().isEmpty());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test void initializationFailureIsReportedWithoutOverwritingFile() throws Exception {
        Path file = directory.resolve("not-a-directory");
        Files.writeString(file, "keep me");
        TaskStorageException error = assertThrows(TaskStorageException.class,
                () -> new SqliteTaskRepository(file.resolve("tasks.db")));
        assertNotNull(error.getCause());
        String output = runConsole("", "initialization-error.log", file.resolve("tasks.db"), 1);
        assertEquals("无法初始化任务数据库，请检查存储配置和访问权限", output.strip());
        assertEquals("keep me", Files.readString(file));
    }

    @Test void lockedWriteFailsClearlyAndCanBeRetriedAfterRollback() throws Exception {
        SqliteTaskRepository repository = new SqliteTaskRepository(database());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            try {
                assertThrows(TaskStorageException.class, () -> repository.add(draft("blocked")));
            } finally {
                statement.execute("ROLLBACK");
            }
        }
        assertTrue(repository.findIncomplete().isEmpty());
        assertTrue(repository.add(draft("retry")).getId() > 0);
    }

    @Test void consoleRetriesInvalidCreationFieldsAndSavesOnlyOnce() throws Exception {
        String output = runConsole("1\n   \ncontent\n\ncreator\n   \nmail\n\n2099-02-30 12:00\n2020-01-01 12:00\n2099-02-28 12:00\n0\n", "create-retry.log");
        for (String message : new String[] {"任务内容不能为空", "创建者不能为空", "提交方式不能为空",
                "截止时间不能为空", "日期不存在或时间格式错误", "截止时间必须晚于当前时间", "任务添加成功，ID： 1"}) {
            assertTrue(output.contains(message), output);
        }
        var tasks = new SqliteTaskRepository(database()).findAll();
        assertEquals(1, tasks.size());
        assertEquals("content", tasks.getFirst().getContent());
        assertEquals("creator", tasks.getFirst().getCreator());
        assertEquals("mail", tasks.getFirst().getMethod());
        assertEquals(LocalDateTime.of(2099, 2, 28, 12, 0), tasks.getFirst().getEndTime());
    }

    @Test void separateJavaProcessesPersistTasksAndContinueIds() throws Exception {
        String firstOutput = runConsole("1\nfirst\nuser\nmail\n2099-01-01 12:00\n4\n1\n0\n", "first.log");
        assertTrue(firstOutput.contains("任务添加成功，ID： 1"), firstOutput);
        String secondOutput = runConsole("2\n4\n1\n0\n1\nsecond\nuser\nmail\n2099-01-01 12:00\n0\n", "second.log");
        assertTrue(secondOutput.contains("任务状态: 已完成"), secondOutput);
        assertTrue(secondOutput.contains("任务添加成功，ID： 2"), secondOutput);
        assertEquals(1, new SqliteTaskRepository(database()).findIncomplete().size());
    }

    @Test void updatesFieldsAndFiltersWhilePreservingIdentity() {
        TaskService service = new TaskService(new SqliteTaskRepository(database()));
        assertTrue(service.getAllTasks().isEmpty());
        assertTrue(service.getCompletedTasks().isEmpty());
        Task first = service.addTask(draft("first"));
        Task other = service.addTask(draft("other"));
        LocalDateTime deadline = LocalDateTime.now().plusDays(3);
        String content = "修改 '); DROP TABLE tasks; --";
        assertTrue(service.updateTask(first.getId(), content, " 新创建者 ", deadline, true));
        TaskService reopened = new TaskService(new SqliteTaskRepository(database()));
        Task loaded = reopened.findTaskById(first.getId());
        assertEquals(content, loaded.getContent());
        assertEquals("新创建者", loaded.getCreator());
        assertEquals(deadline, loaded.getEndTime());
        assertEquals(first.getStartTime(), loaded.getStartTime());
        assertEquals(first.getMethod(), loaded.getMethod());
        assertEquals(first.getId(), loaded.getId());
        assertTrue(loaded.isCompleted());
        assertEquals(java.util.List.of(first.getId(), other.getId()),
                reopened.getAllTasks().stream().map(Task::getId).toList());
        assertEquals(first.getId(), reopened.getCompletedTasks().getFirst().getId());
        assertEquals(other.getId(), reopened.getIncompleteTasks().getFirst().getId());
        assertTrue(reopened.updateTask(first.getId(), content, "新创建者", deadline, false));
        assertFalse(service.findTaskById(first.getId()).isCompleted());
        assertTrue(service.getCompletedTasks().isEmpty());
        assertEquals(2, service.getIncompleteTasks().size());
        assertFalse(service.updateTask(Long.MAX_VALUE, content, "用户", deadline, true));
        assertFalse(new SqliteTaskRepository(database()).update(Long.MAX_VALUE, content, "用户", deadline, true));
        assertEquals("other", service.findTaskById(other.getId()).getContent());
    }

    @Test void invalidUpdatesLeaveStoredTaskUnchangedAndExpiredDeadlineCanBeKept() throws Exception {
        TaskService service = new TaskService(new SqliteTaskRepository(database()));
        Task saved = service.addTask(draft("original"));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(saved.getId(), "  ", "user", saved.getEndTime(), true));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(saved.getId(), "new", "", saved.getEndTime(), true));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(saved.getId(), "new", "user", null, true));
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 12, 0);
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(saved.getId(), "new", "user", past, true));
        Task unchanged = service.findTaskById(saved.getId());
        assertEquals(saved.getContent(), unchanged.getContent());
        assertEquals(saved.getCreator(), unchanged.getCreator());
        assertEquals(saved.getEndTime(), unchanged.getEndTime());
        assertFalse(unchanged.isCompleted());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.prepareStatement("UPDATE tasks SET end_time = ? WHERE id = ?")) {
            statement.setString(1, past.toString());
            statement.setLong(2, saved.getId());
            statement.executeUpdate();
        }
        assertTrue(service.updateTask(saved.getId(), "expired edit", "user", past, true));
        assertEquals(past, service.findTaskById(saved.getId()).getEndTime());
    }

    @Test void consoleEditsValidateInputAndPersistAcrossProcesses() throws Exception {
        TaskService service = new TaskService(new SqliteTaskRepository(database()));
        Task saved = service.addTask(draft("original"));
        String output = runConsole("3\n" + saved.getId()
                + "\n   \nnew content\n   \nnew creator\n2099-02-30 12:00\n2020-01-01 12:00\n2099-02-28 12:00\nyes\ntrue\n0\n", "edit.log");
        assertTrue(output.contains("任务内容不能为空"), output);
        assertTrue(output.contains("创建者不能为空"), output);
        assertTrue(output.contains("日期不存在或时间格式错误"), output);
        assertTrue(output.contains("新的截止时间必须晚于当前时间"), output);
        assertTrue(output.contains("完成状态只能输入 true 或 false"), output);
        assertTrue(output.contains("任务修改成功"), output);
        Task edited = service.findTaskById(saved.getId());
        assertEquals("new content", edited.getContent());
        assertEquals("new creator", edited.getCreator());
        assertEquals(LocalDateTime.of(2099, 2, 28, 12, 0), edited.getEndTime());
        assertTrue(edited.isCompleted());
        String reopened = runConsole("2\n4\n" + saved.getId() + "\n0\n0\n", "reopen-edit.log");
        assertTrue(reopened.contains("任务内容: new content"), reopened);
        assertTrue(reopened.contains("创建者: new creator"), reopened);
        assertTrue(reopened.contains("截止时间: 2099-02-28T12:00"), reopened);
        assertTrue(reopened.contains("任务状态: 已完成"), reopened);
        runConsole("3\n" + saved.getId() + "\n\n\n\nfalse\n0\n", "reopen-task.log");
        Task kept = service.findTaskById(saved.getId());
        assertEquals(edited.getContent(), kept.getContent());
        assertEquals(edited.getCreator(), kept.getCreator());
        assertEquals(edited.getEndTime(), kept.getEndTime());
        assertFalse(kept.isCompleted());
        runConsole("3\n" + saved.getId() + "\n\n\n\n\n0\n", "keep.log");
        assertFalse(service.findTaskById(saved.getId()).isCompleted());
        String cancelled = runConsole("3\n" + saved.getId() + "\nuncommitted\n", "edit-eof.log");
        assertTrue(cancelled.contains("已取消修改"), cancelled);
        assertEquals(edited.getContent(), service.findTaskById(saved.getId()).getContent());
        String missing = runConsole("3\n999\n0\n", "edit-missing.log");
        assertTrue(missing.contains("未找到该任务"), missing);
    }

    @Test void viewSubmenuListsAndFiltersTasksAndReturnsToMain() throws Exception {
        TaskService service = new TaskService(new SqliteTaskRepository(database()));
        Task pending = service.addTask(draft("pending-marker"));
        Task done = service.addTask(draft("done-marker"));
        service.completeTask(done.getId());
        String all = runConsole("2\n1\n0\n0\n", "all.log");
        assertTrue(all.contains("任务内容: pending-marker"), all);
        assertTrue(all.contains("任务内容: done-marker"), all);
        String incomplete = runConsole("2\n2\n0\n0\n", "incomplete.log");
        assertTrue(incomplete.contains("任务内容: pending-marker"), incomplete);
        assertFalse(incomplete.contains("任务内容: done-marker"), incomplete);
        String completed = runConsole("2\n3\n0\n0\n", "completed.log");
        assertTrue(completed.contains("任务内容: done-marker"), completed);
        assertFalse(completed.contains("任务内容: pending-marker"), completed);
        service.deleteTask(pending.getId());
        String empty = runConsole("2\nx\n2\n0\n0\n", "empty.log");
        assertTrue(empty.contains("输入无效"), empty);
        assertTrue(empty.contains("暂无符合条件的任务"), empty);
        assertTrue(empty.contains("程序退出"), empty);
    }

    private String runConsole(String input, String logName) throws Exception {
        return runConsole(input, logName, database(), 0);
    }

    private String runConsole(String input, String logName, Path databasePath, int expectedExit) throws Exception {
        Path output = directory.resolve(logName);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-Dstdin.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dfile.encoding=UTF-8",
                "-Dtask.db=" + databasePath, "-cp", classpath, Main.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(expectedExit, process.exitValue(), Files.readString(output));
            return Files.readString(output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
