package com.hrms.messaging;

import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class MessageConsumer {
  private final MessageProcessor processor;
  private final BrokerSender sender;
  private final MeterRegistry metrics;

  public MessageConsumer(MessageProcessor processor, BrokerSender sender, MeterRegistry metrics) {
    this.processor = processor;
    this.sender = sender;
    this.metrics = metrics;
  }

  @RabbitListener(
      id = "hrms-worker",
      queues = BrokerConfig.WORK,
      autoStartup = "${hrms.listeners-enabled:true}")
  public void receive(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    var props = message.getMessageProperties();
    int attempt = ((Number) props.getHeaders().getOrDefault("attempt", 0)).intValue();
    try {
      processor.process(
          props.getMessageId(),
          (String) props.getHeaders().get("eventType"),
          (String) props.getHeaders().get("aggregateId"));
      channel.basicAck(tag, false);
      metrics.counter("hrms.messages.processed").increment();
    } catch (Exception e) {
      metrics.counter("hrms.messages.failures").increment();
      if (attempt >= 3) {
        channel.basicReject(tag, false);
        metrics.counter("hrms.messages.dead").increment();
        org.slf4j.LoggerFactory.getLogger(getClass())
            .error("Message dead-lettered eventId={}", props.getMessageId());
        return;
      }
      try {
        var retry =
            MessageBuilder.fromClonedMessage(message)
                .setHeader("attempt", attempt + 1)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        sender.send("retry." + attempt, retry);
        channel.basicAck(tag, false);
        metrics.counter("hrms.messages.retries").increment();
      } catch (Exception brokerFailure) {
        channel.basicNack(tag, false, true);
      }
    }
  }
}
