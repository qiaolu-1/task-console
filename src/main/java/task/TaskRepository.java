package task;

import java.util.List;

public interface TaskRepository {
    Task add(Task task);
    Task findById(long id);
    List<Task> findIncomplete();
    boolean complete(long id);
}
