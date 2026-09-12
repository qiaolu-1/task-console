package task.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import task.model.Task;
import task.exception.TaskNotFoundException;
import task.repository.SqliteTaskRepository;

import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class TaskServiceTest {
    @TempDir Path directory;

    @Test void serviceHandlesMissingTasksAndRejectsInvalidIds() {
        TaskService service = new TaskService(new SqliteTaskRepository(directory.resolve("tasks.db")));
        TaskNotFoundException missing = assertThrows(TaskNotFoundException.class, () -> service.requireTask(1));
        assertEquals(1, missing.getTaskId());
        assertFalse(service.deleteTask(1));
        assertFalse(service.completeTask(1));
        assertFalse(service.updateTask(1, "content", "creator", LocalDateTime.now().plusDays(1), false));
        assertThrows(IllegalArgumentException.class, () -> service.requireTask(0));
        assertThrows(IllegalArgumentException.class, () -> service.findTaskById(-1));
        assertThrows(IllegalArgumentException.class, () -> service.deleteTask(0));
        assertThrows(IllegalArgumentException.class, () -> service.completeTask(-1));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateTask(0, "content", "creator", LocalDateTime.now().plusDays(1), false));
    }

    @Test void rechecksTaskAfterItWasDisplayedAndDeletedElsewhere() {
        Path database = directory.resolve("tasks.db");
        TaskService service = new TaskService(new SqliteTaskRepository(database));
        Task saved = service.addTask("task", "user", "mail", LocalDateTime.now().plusDays(1));
        assertEquals(saved.getId(), service.requireTask(saved.getId()).getId());
        assertTrue(new SqliteTaskRepository(database).delete(saved.getId()));
        assertThrows(TaskNotFoundException.class,
                () -> service.validateUpdatedEndTime(saved.getId(), saved.getEndTime()));
        assertFalse(service.updateTask(saved.getId(), "new", "user", saved.getEndTime(), true));
        assertFalse(service.deleteTask(saved.getId()));
        assertFalse(service.completeTask(saved.getId()));
        assertTrue(service.getAllTasks().isEmpty());
    }

    @Test void completedExpiredTasksRemainEditableAndDeletable() {
        SqliteTaskRepository repository = new SqliteTaskRepository(directory.resolve("tasks.db"));
        TaskService service = new TaskService(repository);
        Task saved = service.addTask("task", "user", "mail", LocalDateTime.now().plusDays(1));
        assertThrows(IllegalArgumentException.class, () -> service.addTask(saved));
        LocalDateTime past = LocalDateTime.of(2020, 1, 1, 12, 0);
        assertTrue(repository.update(saved.getId(), "task", "user", past, true));
        assertTrue(service.requireTask(saved.getId()).isCompleted());
        assertDoesNotThrow(() -> service.validateUpdatedEndTime(saved.getId(), past));
        assertThrows(IllegalArgumentException.class,
                () -> service.validateUpdatedEndTime(saved.getId(), past.plusDays(1)));
        assertTrue(service.updateTask(saved.getId(), " edited ", " user ", past, false));
        Task updated = service.requireTask(saved.getId());
        assertEquals("edited", updated.getContent());
        assertEquals(saved.getStartTime(), updated.getStartTime());
        assertEquals(saved.getMethod(), updated.getMethod());
        assertEquals(past, updated.getEndTime());
        assertFalse(updated.isCompleted());
        assertTrue(service.completeTask(saved.getId()));
        assertTrue(service.deleteTask(saved.getId()));
    }

    @Test void createsAndPersistsTaskFromFieldsWithoutUi() {
        TaskService service = new TaskService(new SqliteTaskRepository(directory.resolve("tasks.db")));
        LocalDateTime deadline = LocalDateTime.now().plusDays(1);
        LocalDateTime before = LocalDateTime.now();
        Task saved = service.addTask(" 内容 ", " 创建者 ", " 邮件 ", deadline);
        assertTrue(saved.getId() > 0);
        assertFalse(saved.isCompleted());
        assertFalse(saved.getStartTime().isBefore(before));
        assertFalse(saved.getStartTime().isAfter(LocalDateTime.now()));
        Task loaded = service.findTaskById(saved.getId());
        assertEquals("内容", loaded.getContent());
        assertEquals("创建者", loaded.getCreator());
        assertEquals("邮件", loaded.getMethod());
        assertEquals(deadline, loaded.getEndTime());
    }

    @Test void rejectsInvalidFieldsWithoutSavingOrAllocatingIds() {
        TaskService service = new TaskService(new SqliteTaskRepository(directory.resolve("tasks.db")));
        LocalDateTime deadline = LocalDateTime.now().plusDays(1);
        assertThrows(IllegalArgumentException.class, () -> service.addTask(" ", "user", "mail", deadline));
        assertThrows(IllegalArgumentException.class, () -> service.addTask("task", null, "mail", deadline));
        assertThrows(IllegalArgumentException.class, () -> service.addTask("task", "user", "", deadline));
        assertThrows(IllegalArgumentException.class, () -> service.addTask("task", "user", "mail", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.addTask("task", "user", "mail", LocalDateTime.now().minusDays(1)));
        assertTrue(service.getAllTasks().isEmpty());
        assertEquals(1, service.addTask("valid", "user", "mail", deadline).getId());
    }
}
