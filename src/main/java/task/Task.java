package task;

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

    // 历史任务可以过期，不能重新执行新建任务的期限校验。
    static Task restore(long id, String content, String creator, String method,
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

    // 完成副本保留原任务身份及时间，不重新校验期限或分配编号。
    private Task(Task original) {
        this.content = original.content;
        this.creator = original.creator;
        this.method = original.method;
        this.id = original.id;
        this.startTime = original.startTime;
        this.endTime = original.endTime;
        this.completed = true;
    }

    public Task withCompleted() {
        return completed ? this : new Task(this);
    }

}
