package task.exception;

public class TaskStorageException extends RuntimeException {
    public TaskStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
