package task;

import java.util.List;
import java.util.Objects;

public class TaskService {
    private final TaskRepository repository;

    public TaskService(TaskRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    public Task findTaskById(long id) {
        return repository.findById(id);
    }

    public boolean completeTask(long id) {
        return repository.complete(id);
    }

    public Task addTask(Task task) {
        return repository.add(Objects.requireNonNull(task));
    }

    public List<Task> getIncompleteTasks() {
        return repository.findIncomplete();
    }
}
