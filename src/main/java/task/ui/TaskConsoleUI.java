package task.ui;

import task.model.Task;
import task.exception.TaskStorageException;
import task.exception.TaskNotFoundException;
import task.service.TaskService;

import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Scanner;
import java.util.Objects;

public class TaskConsoleUI {
    private static final DateTimeFormatter END_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);
    private final TaskService taskService;
    private final Scanner scanner;

    public TaskConsoleUI(TaskService taskService) {
        this(taskService, new Scanner(
                System.in,
                System.getProperty("stdin.encoding", java.nio.charset.Charset.defaultCharset().name())));
    }

    public TaskConsoleUI(TaskService taskService, Scanner scanner) {
        this.taskService = Objects.requireNonNull(taskService);
        this.scanner = Objects.requireNonNull(scanner);
    }

    public void start() {
        try {
            while (true) {
                showMenu();
                String choice = readMenuChoice();
                try {
                    switch (choice) {
                        case "1" -> addTask();
                        case "2" -> viewTasks();
                        case "3" -> editTask();
                        case "4" -> showCompleteResult(taskService.completeTask(
                                readTaskId("请输入要完成的任务 ID: ")));
                        case "5" -> deleteTask();
                        case "0" -> {
                            showExitMessage();
                            return;
                        }
                        default -> showInvalidChoice();
                    }
                } catch (TaskNotFoundException e) {
                    System.out.println(e.getMessage());
                } catch (TaskStorageException e) {
                    System.err.println(e.getMessage() + "，操作未成功，请稍后重试。");
                } catch (IllegalArgumentException e) {
                    System.out.println(e.getMessage() + "，操作未成功。");
                } catch (java.util.NoSuchElementException e) {
                    showExitMessage();
                    return;
                }
            }
        } finally {
            close();
        }
    }

    private void viewTasks() {
        while (true) {
            showViewMenu();
            switch (readMenuChoice()) {
                case "1" -> showTasks(taskService.getAllTasks());
                case "2" -> showTasks(taskService.getIncompleteTasks());
                case "3" -> showTasks(taskService.getCompletedTasks());
                case "4" -> showFindResult(taskService.findTaskById(readTaskId("请输入要查询的任务 ID: ")));
                case "0" -> { return; }
                default -> showInvalidChoice();
            }
        }
    }

    private void editTask() {
        long taskId = readTaskId("请输入要修改的任务 ID: ");
        Task existing = taskService.requireTask(taskId);
        TaskEdit edit = readTaskEdit(existing);
        if (edit != null) {
            showUpdateResult(taskService.updateTask(taskId,
                    edit.content(), edit.creator(), edit.endTime(), edit.completed()));
        }
    }

    private void deleteTask() {
        long taskId = readTaskId("请输入要删除的任务 ID: ");
        Task task = taskService.requireTask(taskId);
        if (confirmDelete(task)) {
            showDeleteResult(taskService.deleteTask(taskId));
        } else {
            showDeleteCancelled();
        }
    }

    public void showMenu() {
        System.out.println();
        System.out.println("===待办事项管理===");
        System.out.println("1. 添加任务");
        System.out.println("2. 查看任务");
        System.out.println("3. 修改任务");
        System.out.println("4. 完成任务");
        System.out.println("5. 删除任务");
        System.out.println("0. 退出");
        System.out.print("请选择：");
    }

    public String readMenuChoice() {
        return scanner.hasNextLine() ? scanner.nextLine() : "0";
    }

    public void showTask(Task task) {
        System.out.println("任务 ID: " + task.getId());
        System.out.println("任务内容: " + task.getContent());
        System.out.println("创建者: " + task.getCreator());
        System.out.println("提交方式: " + task.getMethod());
        System.out.println("创建时间: " + task.getStartTime());
        System.out.println("截止时间: " + task.getEndTime());
        System.out.println("任务状态: " + (task.isCompleted() ? "已完成" : "未完成"));
    }

    private void addTask() {
        String content = readNonBlank("请输入任务内容： ", "任务内容");
        String creator = readNonBlank("请输入创建者： ", "创建者");
        String method = readNonBlank("请输入提交方式： ", "提交方式");


        while (true) {
            System.out.print("请输入截止时间（格式：2026-09-10 23:59）: ");
            String endTimeInput = scanner.nextLine();

            try {
                taskService.validateTaskText(endTimeInput, "截止时间");
                LocalDateTime endTime = LocalDateTime.parse(endTimeInput, END_TIME_FORMATTER);
                Task saved = taskService.addTask(content, creator, method, endTime);
                showTaskAdded(saved);
                return;
            } catch (DateTimeParseException e) {
                showRetryMessage("日期不存在或时间格式错误");
            } catch (IllegalArgumentException e) {
                showRetryMessage(e.getMessage());
            }
        }
    }

    public void showViewMenu() {
        System.out.println("===查看任务===");
        System.out.println("1. 查看全部任务");
        System.out.println("2. 查看未完成任务");
        System.out.println("3. 查看已完成任务");
        System.out.println("4. 按 ID 查找");
        System.out.println("0. 返回主菜单");
        System.out.print("请选择：");
    }

    public void showTasks(List<Task> tasks) {
        if (tasks.isEmpty()) System.out.println("暂无符合条件的任务");
        for (Task task : tasks) {
            showTask(task);
            System.out.println();
        }
    }

    public record TaskEdit(String content, String creator, LocalDateTime endTime, boolean completed) {}

    public TaskEdit readTaskEdit(Task task) {
        showTask(task);
        System.out.println("逐项输入新值，直接回车保留原值；输入结束则取消修改。");
        try {
            String content = readOptionalText("任务内容", task.getContent());
            String creator = readOptionalText("创建者", task.getCreator());
            LocalDateTime endTime = readOptionalEndTime(task.getId(), task.getEndTime());
            boolean completed = readOptionalCompleted(task.isCompleted());
            return new TaskEdit(content, creator, endTime, completed);
        } catch (java.util.NoSuchElementException e) {
            System.out.println("已取消修改");
            return null;
        }
    }

    private String readOptionalText(String field, String original) {
        while (true) {
            System.out.print(field + "（回车保留）：");
            String input = scanner.nextLine();
            if (input.isEmpty()) return original;
            try {
                return taskService.validateTaskText(input, field);
            } catch (IllegalArgumentException e) {
                showRetryMessage(e.getMessage());
            }
        }
    }

    private LocalDateTime readOptionalEndTime(long taskId, LocalDateTime original) {
        while (true) {
            System.out.print("截止时间（yyyy-MM-dd HH:mm，回车保留）：");
            String input = scanner.nextLine();
            if (input.isEmpty()) return original;
            try {
                LocalDateTime value = LocalDateTime.parse(input.trim(), END_TIME_FORMATTER);
                taskService.validateUpdatedEndTime(taskId, value);
                return value;
            } catch (DateTimeParseException e) {
                showRetryMessage("日期不存在或时间格式错误");
            } catch (IllegalArgumentException e) {
                showRetryMessage(e.getMessage());
            }
        }
    }

    private boolean readOptionalCompleted(boolean original) {
        while (true) {
            System.out.print("完成状态（true=已完成，false=未完成，回车保留）：");
            String input = scanner.nextLine();
            if (input.isEmpty()) return original;
            if ("true".equals(input.trim())) return true;
            if ("false".equals(input.trim())) return false;
            showRetryMessage("完成状态只能输入 true 或 false");
        }
    }

    public void showUpdateResult(boolean success) {
        System.out.println(success ? "任务修改成功" : "未找到该任务，可能已被删除");
    }

    public void showCompleteResult(boolean success) {
        if (success) {
            System.out.println("任务已标记为完成");
        } else {
            System.out.println("未找到该任务");
        }
    }

    public boolean confirmDelete(Task task) {
        showTask(task);
        System.out.print("删除后无法恢复，确认删除该任务？输入 y 确认，其他输入取消：");
        return scanner.hasNextLine() && "y".equalsIgnoreCase(scanner.nextLine().trim());
    }

    public void showDeleteResult(boolean success) {
        System.out.println(success ? "任务已删除" : "未找到该任务，可能已被删除");
    }

    public void showDeleteCancelled() {
        System.out.println("已取消删除");
    }

    public void showFindResult(Task task) {
        if (task != null) {
            showTask(task);
        } else {
            System.out.println("未找到该任务");
        }
    }

    public long readTaskId(String prompt) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();

            try {
                return taskService.validateTaskId(Long.parseLong(input));
            } catch (IllegalArgumentException e) {
                // 格式错误、数值溢出与非正数共用下面的错误提示。
            }
            showRetryMessage("ID必须是正整数");
        }
    }

    public void close() {
        scanner.close();
    }

    public void showTaskAdded(Task task) {
        System.out.println("任务添加成功，ID： " + task.getId());
    }

    public void showExitMessage() {
        System.out.println("程序退出。");
    }

    public void showInvalidChoice() {
        showRetryMessage("输入无效");
    }

    private void showRetryMessage(String message) {
        System.out.println(message + "，请重新输入。");
    }

    private String readNonBlank(String prompt, String fieldName) {
        while (true) {
            System.out.print(prompt);
            String input = scanner.nextLine().trim();

            try {
                return taskService.validateTaskText(input, fieldName);
            } catch (IllegalArgumentException e) {
                showRetryMessage(e.getMessage());
            }
        }
    }

}
