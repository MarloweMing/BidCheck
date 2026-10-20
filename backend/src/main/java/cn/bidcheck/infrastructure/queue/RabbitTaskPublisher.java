package cn.bidcheck.infrastructure.queue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="bidcheck.queue.enabled",havingValue="true")
public class RabbitTaskPublisher implements TaskNotificationPublisher {
    private static final Logger log=LoggerFactory.getLogger(RabbitTaskPublisher.class);
    private final RabbitTemplate rabbit;
    private final String exchange,routingKey;
    private final long timeout;
    private volatile long retryAfter;
    public RabbitTaskPublisher(@Qualifier("taskRabbitTemplate") RabbitTemplate rabbit,
            @Value("${bidcheck.queue.exchange:bidcheck.tasks.v1}") String exchange,
            @Value("${bidcheck.queue.routing-key:ready}") String routingKey,
            @Value("${bidcheck.queue.confirm-timeout-ms:2000}") long timeout) {
        this.rabbit=rabbit;this.exchange=exchange;this.routingKey=routingKey;this.timeout=Math.max(100,timeout);
    }
    @Override public boolean publishConfirmed(String taskId) {
        if(System.currentTimeMillis()<retryAfter) return false;
        MessageProperties properties=new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(taskId);
        CorrelationData correlation=new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbit.send(exchange,routingKey,new Message(taskId.getBytes(StandardCharsets.UTF_8),properties),correlation);
            CorrelationData.Confirm confirm=correlation.getFuture().get(timeout,TimeUnit.MILLISECONDS);
            // An exchange ACK does not mean routing succeeded; mandatory returns must also be absent.
            if(confirm.isAck() && correlation.getReturned()==null) return true;
            log.warn("任务 {} 的消息未确认路由；由数据库扫描执行",taskId);
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch(Exception unavailable) {
            log.warn("任务通知不可用，切换数据库执行 ({})",unavailable.getClass().getSimpleName());
        }
        retryAfter=System.currentTimeMillis()+5000;
        return false;
    }
}
