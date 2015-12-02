package com.instaclick.pentaho.plugin.amqp.processor;

import com.instaclick.pentaho.plugin.amqp.initializer.Initializer;
import com.instaclick.pentaho.plugin.amqp.AMQPPlugin;
import com.instaclick.pentaho.plugin.amqp.AMQPPluginData;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ConsumerCancelledException;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.QueueingConsumer;
import com.rabbitmq.client.ShutdownSignalException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.pentaho.di.core.exception.KettleStepException;

public class WaitingConsumerProcessor extends BaseConsumerProcessor
{
    final QueueingConsumer consumer;
    String consumerTag;

    public WaitingConsumerProcessor(final Channel channel, final AMQPPlugin plugin, final AMQPPluginData data, final List<Initializer> initializers, final QueueingConsumer consumer)
    {
        super(channel, plugin, data, initializers);

        this.consumer = consumer;
    }

    public WaitingConsumerProcessor(final Channel channel, final AMQPPlugin plugin, final AMQPPluginData data, final List<Initializer> initializers)
    {
        this(channel, plugin, data, initializers, createConsumer(channel, plugin));
    }

    @Override   
    public void start() throws KettleStepException, IOException
    {
        super.start();

        plugin.logMinimal("Waiting for messages : " + data.waitTimeout);
        consumerTag = channel.basicConsume(data.target, false, consumer);
    }

    @Override
    public void cancel() throws IOException
    {
        plugin.logBasic("HAVE TO CANCEL waiting mode.");
        channel.basicCancel(consumerTag);
    }


    @Override
    protected boolean consume() throws IOException, KettleStepException
    {
        final QueueingConsumer.Delivery delivery;

        try {
            delivery = consumer.nextDelivery(data.waitTimeout);
        } catch (InterruptedException ex) {
            throw new KettleStepException(ex.getMessage(), ex);
        } catch (ShutdownSignalException ex) {
            throw new KettleStepException(ex.getMessage(), ex);
        } catch (ConsumerCancelledException ex) {
            throw new KettleStepException(ex.getMessage(), ex);
        }

        if (delivery == null) {
            return false;
        }

        final byte[] body       = delivery.getBody();
        final Envelope envelope = delivery.getEnvelope();
        final long tag          = envelope.getDeliveryTag();
        // final HashMap<String,Object> headers = (HashMap)delivery.getProperties().getHeaders(); // got from: https://www.rabbitmq.com/releases/rabbitmq-java-client/v3.5.6/rabbitmq-java-client-javadoc-3.5.6/com/rabbitmq/client/QueueingConsumer.Delivery.html
        final Map<String,Object> headers;
        if ( delivery.getProperties() != null )
            headers = delivery.getProperties().getHeaders();
        else
            headers = null;

        data.routing = envelope.getRoutingKey();
        data.body    = new String(body);
        data.amqpTag = tag;
        data.headers = headers;

        return true;
    }

    protected static QueueingConsumer createConsumer(final Channel channel, final AMQPPlugin plugin)
    {
        return new QueueingConsumer(channel) {
            @Override
            public void handleCancel(String consumerTag) throws IOException
            {
                plugin.logBasic(consumerTag + " Canceled");
            }

            @Override
            public void handleShutdownSignal(String consumerTag, ShutdownSignalException sig)
            {
                plugin.logDebug(consumerTag + " :SHUTDOWN: " + sig.getMessage());
            }
        };
    }
}
