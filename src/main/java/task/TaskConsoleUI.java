package task;

import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Scanner;

public class TaskConsoleUI {
    private final Scanner scanner;

    public TaskConsoleUI() {
        this.scanner = new Scanner(
                System.in,
                System.getProperty("stdin.encoding", java.nio.charset.Charset.defaultCharset().name()));
    }

    public TaskConsoleUI(Scanner scanner) {
        this.scanner = scanner;
    }

    public void showMenu() {
        System.out.println();
        System.out.println("===待办事项管理===");
        System.out.println("1. 添加任务");
        System.out.println("2. 查看未完成任务");
        System.out.println("3. 完成任务");
        System.out.println("4. 查找任务");
        System.out.println("0. 退出");
        System.out.print("请选择：");
    }

    public String readMenuChoice() {
        return scanner.nextLine();
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

    public Task createTask() {
        String content = readNonBlank("请输入任务内容： ", "任务内容");
        String creator = readNonBlank("请输入创建者： ", "创建者");
        String method = readNonBlank("请输入提交方式： ", "提交方式");


        while (true) {
            System.out.print("请输入截止时间（格式：2026-09-10 23:59）: ");
            String endTimeInput = scanner.nextLine();

            try {
                TaskValidator.requireNonBlank(endTimeInput, "截止时间");
                LocalDateTime endTime = TaskValidator.parseEndTime(endTimeInput);
                return Task.create(content, creator, method, endTime);
            } catch (DateTimeParseException e) {
                showRetryMessage("日期不存在或时间格式错误");
            } catch (IllegalArgumentException e) {
                showRetryMessage(e.getMessage());
            }
        }
    }

    public void showIncompleteTasks(List<Task> tasks) {
        for (Task task : tasks) {
            System.out.println(task.getId() + " - " + task.getContent());
        }
    }

    public void showCompleteResult(boolean success) {
        if (success) {
            System.out.println("任务已标记为完成");
        } else {
            System.out.println("未找到该任务");
        }
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
                long id = Long.parseLong(input);

                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException e) {
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
                return TaskValidator.requireNonBlank(input, fieldName);
            } catch (IllegalArgumentException e) {
                showRetryMessage(e.getMessage());
            }
        }
    }

}
