package cn.bidcheck.infrastructure.queue;

/** Notifications never contain the unique business state; qc_task remains authoritative. */
public interface TaskNotificationPublisher {
    boolean publishConfirmed(String taskId);
}
