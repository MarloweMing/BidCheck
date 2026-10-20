package cn.bidcheck.infrastructure.queue;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@EnableRabbit
@ConditionalOnProperty(name="bidcheck.queue.enabled",havingValue="true")
public class TaskQueueConfiguration {
    @Bean public Queue taskQueue(@Value("${bidcheck.queue.name:bidcheck.task.ready.v1}") String name) {
        // Overflow rejects notifications instead of growing indefinitely; DB tasks remain available.
        return QueueBuilder.durable(name).maxLength(10000).overflow(QueueBuilder.Overflow.rejectPublish).build();
    }
    @Bean public DirectExchange taskExchange(@Value("${bidcheck.queue.exchange:bidcheck.tasks.v1}") String name) { return new DirectExchange(name,true,false); }
    @Bean public Binding taskBinding(Queue taskQueue,DirectExchange taskExchange,@Value("${bidcheck.queue.routing-key:ready}") String key) {
        return BindingBuilder.bind(taskQueue).to(taskExchange).with(key);
    }
    @Bean public RabbitTemplate taskRabbitTemplate(ConnectionFactory connections) {
        RabbitTemplate template=new RabbitTemplate(connections);template.setMandatory(true);return template;
    }
    @Bean public SimpleRabbitListenerContainerFactory taskListenerFactory(ConnectionFactory connections) {
        SimpleRabbitListenerContainerFactory factory=new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connections);factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setConcurrentConsumers(1);factory.setMaxConcurrentConsumers(1);factory.setPrefetchCount(2);
        factory.setMissingQueuesFatal(false);factory.setDefaultRequeueRejected(false);factory.setRecoveryInterval(5000L);
        return factory;
    }
}
