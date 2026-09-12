package task.service;

import task.model.Task;
import task.exception.TaskNotFoundException;
import task.repository.TaskRepository;
import task.validation.TaskValidator;

import java.util.List;
import java.util.Objects;
import java.time.LocalDateTime;

public class TaskService {
    private final TaskRepository repository;

    public TaskService(TaskRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Task findTaskById(long id) {
        validateTaskId(id);
        return repository.findById(id);
    }

    // 当前所有已保存任务均允许修改和删除，包括已完成、已过期的任务。
    public Task requireTask(long id) {
        Task task = findTaskById(id);
        if (task == null) throw new TaskNotFoundException(id);
        return task;
    }

    public long validateTaskId(long id) {
        if (id <= 0) throw new IllegalArgumentException("ID必须是正整数");
        return id;
    }

    public boolean completeTask(long id) {
        validateTaskId(id);
        // SQL 影响行数决定任务是否存在，避免先查询再写入的竞态。
        return repository.complete(id);
    }

    public boolean deleteTask(long id) {
        validateTaskId(id);
        return repository.delete(id);
    }

    public Task addTask(Task task) {
        Objects.requireNonNull(task).requireNew();
        return repository.add(task);
    }

    public Task addTask(String content, String creator, String method, LocalDateTime endTime) {
        return addTask(Task.create(content, creator, method, endTime));
    }

    // 支持交互界面逐项反馈；保存时仍会校验完整数据。
    public String validateTaskText(String value, String fieldName) {
        return TaskValidator.requireNonBlank(value, fieldName);
    }

    public void validateUpdatedEndTime(long id, LocalDateTime endTime) {
        TaskValidator.validateUpdatedEndTime(endTime, requireTask(id).getEndTime());
    }

    public List<Task> getIncompleteTasks() {
        return repository.findIncomplete();
    }

    public List<Task> getAllTasks() {
        return repository.findAll();
    }

    public List<Task> getCompletedTasks() {
        return repository.findCompleted();
    }

    public boolean updateTask(long id, String content, String creator,
                              LocalDateTime endTime, boolean completed) {
        Task existing = findTaskById(id);
        if (existing == null) return false;
        Task updated = existing.withUpdates(content, creator, endTime, completed);
        return repository.update(id, updated.getContent(), updated.getCreator(),
                updated.getEndTime(), updated.isCompleted());
    }
}
