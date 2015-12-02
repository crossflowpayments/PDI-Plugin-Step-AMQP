package com.instaclick.pentaho.plugin.amqp.processor;

import com.instaclick.pentaho.plugin.amqp.initializer.Initializer;
import com.instaclick.pentaho.plugin.amqp.AMQPPlugin;
import com.instaclick.pentaho.plugin.amqp.AMQPPluginData;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.List;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowDataUtil;

abstract class BaseConsumerProcessor extends BaseProcessor
{
    public BaseConsumerProcessor(final Channel channel, final AMQPPlugin plugin, final AMQPPluginData data, final List<Initializer> initializers)
    {
        super(channel, plugin, data, initializers);
    }

    @Override
    public void onSuccess() throws IOException
    {
        if ( ! data.isTransactional) {
            return;
        }

        if ( data.activeConfirmation)  {
            flushActiveConfirmation();
            return;
        }

        plugin.logMinimal("Ack All messages : " + data.amqpTag);
        channel.basicAck(data.amqpTag, true);
        data.ack = data.count;
    }

    @Override
    public void onFailure() throws IOException
    {
        if ( ! data.isTransactional) {
            return;
        }

        plugin.logMinimal("Ignoring messages : " + data.amqpTag);
        data.amqpTag = -1;
    }

    @Override
    public void shutdown() throws IOException
    {
        super.shutdown();

        final long ack      = data.ack;
        final long rejected = data.rejected;
        final long requeue  = (data.count - ack - rejected);

        plugin.logMinimal("Queue messages received : ack=" + ack + ", rejected=" + rejected + ", requeue=" + requeue);
    }

    @Override
    public boolean process(final Object[] r) throws IOException, KettleStepException
    {
        if ( ! consume()) {
            return false;
        }

        // safely add the unique field at the end of the output row
        final Object[] row = RowDataUtil.allocateRowData(data.outputRowMeta.size());

        row[data.bodyFieldIndex] = data.body;

        if ( data.routingIndex != null ) {
            row[data.routingIndex]   = data.routing;
        }

        if ( data.deliveryTagIndex != null) {
            row[data.deliveryTagIndex] = data.amqpTag;
        }

        // WIP adding header fields and content to row 
        plugin.logDebug("data.headers: " + data.toString() );
        System.out.println("data.headers: " + data.toString() );
        // System.out.println("data.headersNamesFieldsIndexes: " + data.headersNamesFieldsIndexes.toString() );
        if ( data.headers != null && data.headers.size() > 0 ) {
            int headerIndex;
            plugin.logDebug("data.headers: " + data.headers.toString() );
            for( String headerName : data.headers.keySet() ) {
                headerIndex = data.headersNamesFieldsIndexes.get(headerName);
                row[headerIndex] = data.headers.get(headerName);
            }
        }

        // put the row to the output row stream
        plugin.putRow(data.outputRowMeta, row);
        plugin.incrementLinesInput();

        data.count ++;

        if ( ! data.isTransactional && ! data.isRequeue && ! data.activeConfirmation) {
            plugin.logDebug("basicAck : " + data.amqpTag);
            channel.basicAck(data.amqpTag, true);
            data.ack++;
        }

        if (data.count >= data.limit) {
            plugin.logBasic(String.format("Message limit %s", data.count));
            return false;
        }

        return true;
    }

    protected abstract boolean consume() throws IOException, KettleStepException;

    protected void flushActiveConfirmation() throws IOException
    {
        if (data.ackMsgInTransaction != null) {
            // Ack all good
            for (final Long tag : data.ackMsgInTransaction)  {
                channel.basicAck(tag, false);
                data.ack++;
            }

            plugin.logMinimal("Acknowledged messages : " + data.ackMsgInTransaction.size());
            data.ackMsgInTransaction.clear();
        }

        if (data.rejectedMsgInTransaction != null) {
            // Reject all with errors
            for (final Long tag : data.rejectedMsgInTransaction) {
                channel.basicNack(tag, false, false);
                data.rejected++;
            }

            plugin.logMinimal("Rejected messages : " + data.rejectedMsgInTransaction.size());
            data.rejectedMsgInTransaction.clear();
        }
    }
}
