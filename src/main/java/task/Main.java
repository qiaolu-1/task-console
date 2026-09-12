package task;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        TaskService taskService;
        try {
            taskService = new TaskService(new SqliteTaskRepository(
                    Path.of(System.getProperty("task.db", "data/tasks.db"))));
        } catch (TaskStorageException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        TaskConsoleUI taskConsoleUI = new TaskConsoleUI();
        try {
            while (true) {
                taskConsoleUI.showMenu();
                String choice = taskConsoleUI.readMenuChoice();
                try {
                    switch (choice) {
                        case "1":
                            Task task = taskConsoleUI.createTask();
                            Task savedTask = taskService.addTask(task);
                            taskConsoleUI.showTaskAdded(savedTask);
                            break;
                        case "2":
                            taskConsoleUI.showIncompleteTasks(taskService.getIncompleteTasks());
                            break;
                        case "3":
                            long taskId = taskConsoleUI.readTaskId("请输入要完成的任务 ID: ");
                            taskConsoleUI.showCompleteResult(taskService.completeTask(taskId));
                            break;
                        case "4":
                            long findTaskId = taskConsoleUI.readTaskId("请输入要查询的任务 ID: ");
                            taskConsoleUI.showFindResult(taskService.findTaskById(findTaskId));
                            break;
                        case "0":
                            taskConsoleUI.showExitMessage();
                            return;
                        default:
                            taskConsoleUI.showInvalidChoice();
                    }
                } catch (TaskStorageException e) {
                    System.err.println(e.getMessage() + "，操作未成功，请稍后重试。");
                }
            }
        } finally {
            taskConsoleUI.close();
        }
    }
}
