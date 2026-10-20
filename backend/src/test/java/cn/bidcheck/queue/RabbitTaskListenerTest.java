package cn.bidcheck.queue;

import cn.bidcheck.infrastructure.queue.RabbitTaskListener;
import cn.bidcheck.service.TaskWorker;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import static org.mockito.Mockito.*;

class RabbitTaskListenerTest {
    private static final String ID="d850027c-2467-45e7-a833-9e7e2e0f448b";
    private Message message() { MessageProperties p=new MessageProperties();p.setDeliveryTag(7);return new Message(ID.getBytes(StandardCharsets.UTF_8),p); }

    @Test void ackFollowsDurableSubmissionWithoutWaitingForTaskCompletion() throws Exception {
        TaskWorker worker=mock(TaskWorker.class);Channel channel=mock(Channel.class);
        when(worker.tryStart(ID)).thenReturn(TaskWorker.Acceptance.STARTED);
        new RabbitTaskListener(worker).accept(message(),channel);
        var order=inOrder(worker,channel);order.verify(worker).tryStart(ID);order.verify(channel).basicAck(7,false);
    }
    @Test void busySignalIsAcknowledgedBecausePendingDbTaskRemainsRecoverable() throws Exception {
        TaskWorker worker=mock(TaskWorker.class);Channel channel=mock(Channel.class);
        when(worker.tryStart(ID)).thenReturn(TaskWorker.Acceptance.BUSY);
        new RabbitTaskListener(worker).accept(message(),channel);
        verify(channel).basicAck(7,false);verify(channel,never()).basicNack(anyLong(),anyBoolean(),anyBoolean());
    }
    @Test void databaseFailureDoesNotCreateAnInfiniteBrokerRedeliveryLoop() throws Exception {
        TaskWorker worker=mock(TaskWorker.class);Channel channel=mock(Channel.class);
        when(worker.tryStart(ID)).thenThrow(new IllegalStateException("database offline"));
        new RabbitTaskListener(worker).accept(message(),channel);
        verify(channel).basicNack(7,false,false);verify(channel,never()).basicAck(anyLong(),anyBoolean());
    }
    @Test void malformedAndOversizedWakeupsAreRejectedBeforeDatabaseAccess() throws Exception {
        TaskWorker worker=mock(TaskWorker.class);Channel channel=mock(Channel.class);RabbitTaskListener listener=new RabbitTaskListener(worker);
        MessageProperties properties=new MessageProperties();properties.setDeliveryTag(7);
        listener.accept(new Message("------------------------------------".getBytes(StandardCharsets.UTF_8),properties),channel);
        listener.accept(new Message(new byte[4096],properties),channel);
        verify(channel,times(2)).basicReject(7,false);verifyNoInteractions(worker);
    }
}
