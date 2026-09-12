package task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;

/** 每次操作独立连接，不在线程之间共享 JDBC 连接；写入由 SQLite 协调。 */
public final class SqliteTaskRepository implements TaskRepository {
    private final String url;

    public SqliteTaskRepository(Path databasePath) {
        Path absolutePath = Objects.requireNonNull(databasePath).toAbsolutePath().normalize();
        url = "jdbc:sqlite:" + absolutePath;
        try {
            Files.createDirectories(absolutePath.getParent());
            try (Connection connection = open(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        CREATE TABLE IF NOT EXISTS tasks (
                            id INTEGER PRIMARY KEY AUTOINCREMENT,
                            content TEXT NOT NULL,
                            creator TEXT NOT NULL,
                            method TEXT NOT NULL,
                            start_time TEXT NOT NULL,
                            end_time TEXT NOT NULL,
                            completed INTEGER NOT NULL DEFAULT 0 CHECK (completed IN (0, 1))
                        )
                        """);
            }
        } catch (IOException | SQLException e) {
            throw new TaskStorageException("无法初始化任务数据库：" + absolutePath, e);
        }
    }

    private Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("busy_timeout", "5000");
        return DriverManager.getConnection(url, properties);
    }

    @Override
    public Task add(Task task) {
        Objects.requireNonNull(task);
        if (task.getId() != 0 || task.isCompleted()) {
            throw new IllegalArgumentException("只能添加尚未保存且未完成的新任务");
        }
        // 插入和返回编号使用同一条语句，避免并发查询 MAX(id) 的竞争。
        String sql = """
                INSERT INTO tasks (content, creator, method, start_time, end_time, completed)
                VALUES (?, ?, ?, ?, ?, 0) RETURNING *
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, task.getContent());
            statement.setString(2, task.getCreator());
            statement.setString(3, task.getMethod());
            statement.setString(4, task.getStartTime().toString());
            statement.setString(5, task.getEndTime().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("插入未返回任务 ID");
                return readTask(rows);
            }
        } catch (SQLException e) {
            throw new TaskStorageException("保存任务失败，请检查数据库文件和写入占用情况", e);
        }
    }

    @Override
    public Task findById(long id) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("SELECT * FROM tasks WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? readTask(rows) : null;
            }
        } catch (SQLException e) {
            throw new TaskStorageException("查询任务失败", e);
        }
    }

    @Override
    public List<Task> findIncomplete() {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM tasks WHERE completed = 0 ORDER BY id");
             ResultSet rows = statement.executeQuery()) {
            List<Task> tasks = new ArrayList<>();
            while (rows.next()) tasks.add(readTask(rows));
            return List.copyOf(tasks);
        } catch (SQLException e) {
            throw new TaskStorageException("查询未完成任务失败", e);
        }
    }

    @Override
    public boolean complete(long id) {
        // 一条 UPDATE 原子完成状态修改，不做先查后写。
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("UPDATE tasks SET completed = 1 WHERE id = ?")) {
            statement.setLong(1, id);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new TaskStorageException("更新任务状态失败", e);
        }
    }

    private static Task readTask(ResultSet rows) throws SQLException {
        try {
            return Task.restore(rows.getLong("id"), rows.getString("content"),
                    rows.getString("creator"), rows.getString("method"),
                    LocalDateTime.parse(rows.getString("start_time")),
                    LocalDateTime.parse(rows.getString("end_time")), rows.getInt("completed") == 1);
        } catch (java.time.DateTimeException | IllegalArgumentException | NullPointerException e) {
            throw new SQLException("数据库中的任务字段无效", e);
        }
    }
}
