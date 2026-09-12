package task;

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

    @Test void neverReusesDeletedCommittedIdsEvenAfterDeletingAllTasks() throws Exception {
        SqliteTaskRepository repository = new SqliteTaskRepository(database());
        repository.add(draft("one"));
        long highest = repository.add(draft("two")).getId();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM tasks");
        }
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
        assertThrows(TaskStorageException.class, () -> new SqliteTaskRepository(file.resolve("tasks.db")));
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

    @Test void separateJavaProcessesPersistTasksAndContinueIds() throws Exception {
        String firstOutput = runConsole("1\nfirst\nuser\nmail\n2099-01-01 12:00\n3\n1\n0\n", "first.log");
        assertTrue(firstOutput.contains("任务添加成功，ID： 1"), firstOutput);
        String secondOutput = runConsole("4\n1\n1\nsecond\nuser\nmail\n2099-01-01 12:00\n0\n", "second.log");
        assertTrue(secondOutput.contains("任务状态: 已完成"), secondOutput);
        assertTrue(secondOutput.contains("任务添加成功，ID： 2"), secondOutput);
        assertEquals(1, new SqliteTaskRepository(database()).findIncomplete().size());
    }

    private String runConsole(String input, String logName) throws Exception {
        Path output = directory.resolve(logName);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED",
                "-Dstdin.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8",
                "-Dtask.db=" + database(), "-cp", classpath, Main.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), Files.readString(output));
            return Files.readString(output);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }
}
