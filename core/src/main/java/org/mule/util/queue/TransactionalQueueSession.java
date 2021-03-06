/*
 * $Id$
 * --------------------------------------------------------------------------------------
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 *
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.util.queue;

import org.mule.api.store.ObjectStoreException;
import org.mule.util.store.DeserializationPostInitialisable;
import org.mule.util.xa.AbstractXAResourceManager;
import org.mule.util.xa.DefaultXASession;

import java.io.Serializable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * A Queue session that is used to manage the transaction context of a Queue
 */
class TransactionalQueueSession extends DefaultXASession implements QueueSession
{
    private Log logger = LogFactory.getLog(TransactionalQueueSession.class);

    protected TransactionalQueueManager queueManager;

    public TransactionalQueueSession(AbstractXAResourceManager resourceManager,
                                     TransactionalQueueManager queueManager)
    {
        super(resourceManager);
        this.queueManager = queueManager;
    }

    @Override
    public Queue getQueue(String name)
    {
        QueueInfo queue = queueManager.getQueue(name);
        return new QueueImpl(queue);
    }

    protected class QueueImpl implements Queue
    {
        protected QueueInfo queue;

        public QueueImpl(QueueInfo queue)
        {
            this.queue = queue;
        }

        @Override
        public void put(Serializable item) throws InterruptedException, ObjectStoreException
        {
            offer(item, Long.MAX_VALUE);
        }

        @Override
        public boolean offer(Serializable item, long timeout) throws InterruptedException, ObjectStoreException
        {
            if (localContext != null  && !queue.isQueueTransactional())
            {
                return ((QueueTransactionContext) localContext).offer(queue, item, timeout);
            }
            else
            {
                try
                {
                    Serializable id = queueManager.doStore(queue, item);
                    try
                    {
                        if (!queue.offer(id, 0, timeout))
                        {
                            queueManager.doRemove(queue, id);
                            return false;
                        }
                        else
                        {
                            return true;
                        }
                    }
                    catch (InterruptedException e)
                    {
                        queueManager.doRemove(queue, id);
                        throw e;
                    }
                }
                catch (ObjectStoreException e)
                {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public Serializable take() throws InterruptedException
        {
            return poll(Long.MAX_VALUE);
        }

        @Override
        public void untake(Serializable item) throws InterruptedException, ObjectStoreException
        {
            if (localContext != null && !queue.isQueueTransactional())
            {
                ((QueueTransactionContext) localContext).untake(queue, item);
            }
            else
            {
                Serializable id = queueManager.doStore(queue, item);
                queue.untake(id);
            }
        }

        @Override
        public Serializable poll(long timeout) throws InterruptedException
        {
            try
            {
                if (localContext != null && !queue.isQueueTransactional())
                {
                    Serializable item = ((QueueTransactionContext) localContext).poll(queue, timeout);
                    return postProcessIfNeeded(item);
                }
                else if (queue.canTakeFromStore())
                {
                    Serializable item = queue.takeNextItemFromStore(timeout);
                    return postProcessIfNeeded(item);
                }
                else
                {
                    Serializable id = queue.poll(timeout);
                    if (id != null)
                    {
                        Serializable item = queueManager.doLoad(queue, id);
                        if (item != null)
                        {
                            queueManager.doRemove(queue, id);
                        }
                        return postProcessIfNeeded(item);
                    }
                    return null;
                }
            }
            catch (InterruptedException iex)
            {
                if (!queueManager.getMuleContext().isStopping())
                {
                    throw iex;
                }
                // if stopping, ignore
                return null;
            }
            catch (ObjectStoreException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public Serializable peek() throws InterruptedException
        {
            try
            {
                if (localContext != null && !queue.isQueueTransactional())
                {
                    Serializable item = ((QueueTransactionContext) localContext).peek(queue);
                    return postProcessIfNeeded(item);
                }
                else
                {
                    Serializable id = queue.peek();
                    if (id != null)
                    {
                        Serializable item = queueManager.doLoad(queue, id);
                        return postProcessIfNeeded(item);
                    }
                    return null;
                }
            }
            catch (ObjectStoreException e)
            {
                throw new RuntimeException(e);
            }
        }

        @Override
        public int size()
        {
            if (localContext != null && !queue.isQueueTransactional())
            {
                return ((QueueTransactionContext) localContext).size(queue);
            }
            else
            {
                return queue.getSize();
            }
        }

        @Override
        public String getName()
        {
            return queue.getName();
        }

        /**
         *  Note -- this must handle null items
         */
        private Serializable postProcessIfNeeded(Serializable item)
        {
            try
            {
                if (item instanceof DeserializationPostInitialisable)
                {
                    DeserializationPostInitialisable.Implementation.init(item, queueManager.getMuleContext());
                }
                return item;
            }
            catch (Exception e)
            {
                logger.warn("Unable to deserialize message", e);
                return null;
            }
        }
    }
}
