package task.exception;

public class TaskNotFoundException extends RuntimeException {
    public TaskNotFoundException(long id) {
        super("未找到该任务");
        this.taskId = id;
    }

    private final long taskId;

    public long getTaskId() {
        return taskId;
    }
}
