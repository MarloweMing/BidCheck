package cn.bidcheck.infrastructure.queue;

/** The durable task is now owned by another execution; stale workers must stop writing. */
public final class TaskLeaseLostException extends IllegalStateException {
    public TaskLeaseLostException(String taskId) { super("任务租约已失效："+taskId); }
}
