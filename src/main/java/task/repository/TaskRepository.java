package task.repository;

import task.model.Task;

import java.util.List;
import java.time.LocalDateTime;

public interface TaskRepository {
    Task add(Task task);
    Task findById(long id);
    List<Task> findIncomplete();
    List<Task> findAll();
    List<Task> findCompleted();
    boolean update(long id, String content, String creator, LocalDateTime endTime, boolean completed);
    boolean complete(long id);
    boolean delete(long id);
}
