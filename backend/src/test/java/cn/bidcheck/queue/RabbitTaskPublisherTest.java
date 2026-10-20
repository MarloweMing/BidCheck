package cn.bidcheck.queue;

import cn.bidcheck.infrastructure.queue.RabbitTaskPublisher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RabbitTaskPublisherTest {
    @Test void sendsOnlyTaskIdAsPersistentNotificationAndWaitsForPublisherConfirm() {
        RabbitTemplate template=mock(RabbitTemplate.class);
        doAnswer(a->{CorrelationData c=a.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(template).send(eq("tasks"),eq("ready"),any(Message.class),any(CorrelationData.class));
        assertThat(new RabbitTaskPublisher(template,"tasks","ready",100).publishConfirmed("task-id")).isTrue();
        ArgumentCaptor<Message> body=ArgumentCaptor.forClass(Message.class);
        verify(template).send(eq("tasks"),eq("ready"),body.capture(),any(CorrelationData.class));
        assertThat(new String(body.getValue().getBody(),StandardCharsets.UTF_8)).isEqualTo("task-id");
        assertThat(body.getValue().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }
    @Test void exchangeAckWithMandatoryReturnIsNotTreatedAsDelivered() {
        RabbitTemplate template=mock(RabbitTemplate.class);
        doAnswer(a->{CorrelationData c=a.getArgument(3);c.setReturned(new ReturnedMessage(a.getArgument(2),312,"NO_ROUTE","tasks","ready"));c.getFuture().complete(new CorrelationData.Confirm(true,null));return null;})
            .when(template).send(eq("tasks"),eq("ready"),any(Message.class),any(CorrelationData.class));
        assertThat(new RabbitTaskPublisher(template,"tasks","ready",100).publishConfirmed("task-id")).isFalse();
    }
    @Test void brokerDisconnectFallsBackWithoutReportingFalseDelivery() {
        RabbitTemplate template=mock(RabbitTemplate.class);
        doThrow(new org.springframework.amqp.AmqpConnectException(new java.net.ConnectException("offline")))
            .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        assertThat(new RabbitTaskPublisher(template,"tasks","ready",100).publishConfirmed("task-id")).isFalse();
    }
    @Test void negativeConfirmUsesDatabaseFallbackAndDoesNotHammerTheBroker() {
        RabbitTemplate template=mock(RabbitTemplate.class);
        doAnswer(a->{CorrelationData c=a.getArgument(3);c.getFuture().complete(new CorrelationData.Confirm(false,"queue overflow"));return null;})
            .when(template).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
        RabbitTaskPublisher publisher=new RabbitTaskPublisher(template,"tasks","ready",100);
        assertThat(publisher.publishConfirmed("first")).isFalse();assertThat(publisher.publishConfirmed("second")).isFalse();
        verify(template,times(1)).send(anyString(),anyString(),any(Message.class),any(CorrelationData.class));
    }
}
