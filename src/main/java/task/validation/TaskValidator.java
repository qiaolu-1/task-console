package task.validation;

import java.time.LocalDateTime;

public final class TaskValidator {
    private TaskValidator() {
    }

    public static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }
        return value.trim();
    }

    public static void validateUpdatedEndTime(LocalDateTime endTime, LocalDateTime originalEndTime) {
        if (endTime == null) throw new IllegalArgumentException("截止时间不能为空");
        // 允许保留已过期的原截止时间，以便单独修改内容或完成状态。
        if (!endTime.equals(originalEndTime) && !endTime.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("新的截止时间必须晚于当前时间");
        }
    }

    public static ValidatedParameters validate(
            String content, String creator, String method, LocalDateTime endTime) {
        String validContent = requireNonBlank(content, "任务内容");
        String validCreator = requireNonBlank(creator, "创建者");
        String validMethod = requireNonBlank(method, "提交方式");
        if (endTime == null) {
            throw new IllegalArgumentException("截止时间不能为空");
        }
        if (!endTime.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("截止时间必须晚于当前时间");
        }
        return new ValidatedParameters(validContent, validCreator, validMethod, endTime);
    }

    // 私有构造器防止调用方自行包装未经校验的参数。
    public static final class ValidatedParameters {
        private final String content;
        private final String creator;
        private final String method;
        private final LocalDateTime endTime;

        private ValidatedParameters(String content, String creator, String method, LocalDateTime endTime) {
            this.content = content;
            this.creator = creator;
            this.method = method;
            this.endTime = endTime;
        }

        public String getContent() { return content; }
        public String getCreator() { return creator; }
        public String getMethod() { return method; }
        public LocalDateTime getEndTime() { return endTime; }
    }
}
