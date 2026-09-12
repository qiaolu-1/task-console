package task;

import task.exception.TaskStorageException;
import task.repository.SqliteTaskRepository;
import task.repository.TaskRepository;
import task.service.TaskService;
import task.ui.TaskConsoleUI;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        try {
            TaskRepository repository = new SqliteTaskRepository(
                    Path.of(System.getProperty("task.db", "data/tasks.db")));
            TaskService service = new TaskService(repository);
            TaskConsoleUI ui = new TaskConsoleUI(service);
            ui.start();
        } catch (TaskStorageException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
