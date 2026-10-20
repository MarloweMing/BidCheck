package cn.bidcheck.infrastructure.queue;

import cn.bidcheck.service.TaskWorker;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="bidcheck.queue.enabled",havingValue="true")
public class RabbitTaskListener {
    private static final Logger log=LoggerFactory.getLogger(RabbitTaskListener.class);
    private final TaskWorker worker;
    public RabbitTaskListener(TaskWorker worker) { this.worker=worker; }
    @RabbitListener(queues="${bidcheck.queue.name:bidcheck.task.ready.v1}",containerFactory="taskListenerFactory")
    public void accept(Message message,Channel channel) throws IOException {
        long tag=message.getMessageProperties().getDeliveryTag();
        if(message.getBody().length!=36) { channel.basicReject(tag,false);return; }
        String taskId=new String(message.getBody(),StandardCharsets.UTF_8);
        if(!taskId.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) {
            channel.basicReject(tag,false);return;
        }
        try {
            worker.tryStart(taskId);
        } catch(RuntimeException unavailable) {
            // Discard only this wake-up notification. The unclaimed/expired DB task is scanned again.
            // Avoid an unlimited Rabbit redelivery loop during a database outage.
            log.warn("任务 {} 暂无法领取；保留数据库记录等待恢复",taskId);
            channel.basicNack(tag,false,false);return;
        }
        // BUSY and duplicate signals can also be acknowledged: DB retains the only business state.
        // STARTED means a durable claim plus bounded submission, not completion of long OCR/AI work.
        channel.basicAck(tag,false);
    }
}
