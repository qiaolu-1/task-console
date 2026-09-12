package task.model;

import task.validation.TaskValidator;

import java.time.LocalDateTime;
import java.util.Objects;

public final class Task {
    private final String content;
    private final String creator;
    private final String method;
    private final long id;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final boolean completed;

    public static Task create(String content, String creator, String method, LocalDateTime endTime) {
        return new Task(TaskValidator.validate(content, creator, method, endTime));
    }

    // 仅接收校验模块生成的参数，原始输入不能直接进入 ID 分配流程。
    private Task(TaskValidator.ValidatedParameters parameters) {
        this.content = parameters.getContent();
        this.creator = parameters.getCreator();
        this.method = parameters.getMethod();
        this.endTime = parameters.getEndTime();

        this.startTime = LocalDateTime.now();
        this.completed = false;

        // 0 表示尚未保存，正式编号仅由数据库分配。
        this.id = 0;

    }

    public long getId() {
        return id;
    }

    public void requireNew() {
        if (id != 0 || completed) {
            throw new IllegalArgumentException("只能添加尚未保存且未完成的新任务");
        }
    }

    public Task withUpdates(String content, String creator, LocalDateTime endTime, boolean completed) {
        String validContent = TaskValidator.requireNonBlank(content, "任务内容");
        String validCreator = TaskValidator.requireNonBlank(creator, "创建者");
        TaskValidator.validateUpdatedEndTime(endTime, this.endTime);
        return new Task(id, validContent, validCreator, method, startTime, endTime, completed);
    }

    // 历史任务可以过期，不能重新执行新建任务的期限校验。
    public static Task restore(long id, String content, String creator, String method,
                        LocalDateTime startTime, LocalDateTime endTime, boolean completed) {
        return new Task(id, content, creator, method, startTime, endTime, completed);
    }

    private Task(long id, String content, String creator, String method,
                 LocalDateTime startTime, LocalDateTime endTime, boolean completed) {
        if (id <= 0) throw new IllegalArgumentException("已保存任务的 ID 必须为正数");
        this.id = id;
        this.content = Objects.requireNonNull(content);
        this.creator = Objects.requireNonNull(creator);
        this.method = Objects.requireNonNull(method);
        this.startTime = Objects.requireNonNull(startTime);
        this.endTime = Objects.requireNonNull(endTime);
        this.completed = completed;
    }

    public String getContent() {
        return content;
    }

    public String getCreator() {
        return creator;
    }

    public String getMethod() {
        return method;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public boolean isCompleted() {
        return completed;
    }

}
