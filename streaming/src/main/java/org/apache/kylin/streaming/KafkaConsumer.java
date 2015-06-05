/*
 *
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *
 *  contributor license agreements. See the NOTICE file distributed with
 *
 *  this work for additional information regarding copyright ownership.
 *
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *
 *  (the "License"); you may not use this file except in compliance with
 *
 *  the License. You may obtain a copy of the License at
 *
 *
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *
 *  Unless required by applicable law or agreed to in writing, software
 *
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *  See the License for the specific language governing permissions and
 *
 *  limitations under the License.
 *
 * /
 */

package org.apache.kylin.streaming;

import com.google.common.base.Preconditions;
import kafka.cluster.Broker;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.PartitionMetadata;
import kafka.message.MessageAndOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 */
public class KafkaConsumer implements Runnable {

    private final String topic;
    private final int partitionId;
    private final KafkaClusterConfig streamingConfig;
    private final transient int parallelism;
    private final LinkedBlockingQueue<StreamMessage>[] streamQueue;

    private long offset;
    private List<Broker> replicaBrokers;

    protected final Logger logger;

    private volatile boolean isRunning = true;

    public KafkaConsumer(int clusterID, String topic, int partitionId, long startOffset, List<Broker> initialBrokers, KafkaClusterConfig kafkaClusterConfig) {
        this(clusterID, topic, partitionId, startOffset, initialBrokers, kafkaClusterConfig, 1);
    }

    public KafkaConsumer(int clusterID, String topic, int partitionId, long startOffset, List<Broker> initialBrokers, KafkaClusterConfig kafkaClusterConfig, int parallelism) {
        Preconditions.checkArgument(parallelism > 0);
        this.topic = topic;
        this.partitionId = partitionId;
        this.streamingConfig = kafkaClusterConfig;
        this.offset = startOffset;
        this.replicaBrokers = initialBrokers;
        this.logger = LoggerFactory.getLogger(topic + "_cluster_" + clusterID + "_" + partitionId);
        this.parallelism = parallelism;
        this.streamQueue = new LinkedBlockingQueue[parallelism];
        for (int i = 0; i < parallelism; ++i) {
            streamQueue[i] = new LinkedBlockingQueue<StreamMessage>(kafkaClusterConfig.getMaxReadCount());
        }
    }

    public BlockingQueue<StreamMessage> getStreamQueue(int index) {
        return streamQueue[index];
    }

    private Broker getLeadBroker() {
        final PartitionMetadata partitionMetadata = KafkaRequester.getPartitionMetadata(topic, partitionId, replicaBrokers, streamingConfig);
        if (partitionMetadata != null && partitionMetadata.errorCode() == 0) {
            replicaBrokers = partitionMetadata.replicas();
            return partitionMetadata.leader();
        } else {
            return null;
        }
    }

    protected int hash(long offset) {
        return (int) (offset % parallelism);
    }

    @Override
    public void run() {
        try {
            Broker leadBroker = getLeadBroker();
            int consumeMsgCount = 0;
            int fetchRound = 0;
            while (isRunning) {
                int consumeMsgCountAtBeginning = consumeMsgCount;
                fetchRound++;

                if (leadBroker == null) {
                    leadBroker = getLeadBroker();
                }

                if (leadBroker == null) {
                    logger.warn("cannot find lead broker");
                    continue;
                }

                logger.info("fetching topic {} partition id {} offset {} leader {}", new String[] { topic, String.valueOf(partitionId), String.valueOf(offset), leadBroker.toString() });

                final FetchResponse fetchResponse = KafkaRequester.fetchResponse(topic, partitionId, offset, leadBroker, streamingConfig);
                if (fetchResponse.errorCode(topic, partitionId) != 0) {
                    logger.warn("fetch response offset:" + offset + " errorCode:" + fetchResponse.errorCode(topic, partitionId));
                    Thread.sleep(30000);
                    continue;
                }

                for (MessageAndOffset messageAndOffset : fetchResponse.messageSet(topic, partitionId)) {
                    try {
                        consume(messageAndOffset.offset(), messageAndOffset.message().payload());
                    } catch (Exception e) {
                        logger.error("error put streamQueue", e);
                        break;
                    }
                    offset++;
                    consumeMsgCount++;
                }
                logger.info("Number of messages consumed: " + consumeMsgCount + " offset is: " + offset + " total fetch round: " + fetchRound);

                if (consumeMsgCount == consumeMsgCountAtBeginning)//nothing this round
                {
                    Thread.sleep(30000);
                }
            }
            for (int i = 0; i < streamQueue.length; ++i) {
                streamQueue[i].put(StreamMessage.EOF);
            }
        } catch (Exception e) {
            logger.error("consumer has encountered an error", e);
        }
    }

    protected void consume(long offset, ByteBuffer payload) throws Exception {
        byte[] bytes = new byte[payload.limit()];
        payload.get(bytes);
        StreamMessage newStreamMessage = new StreamMessage(offset, bytes);
        while (true) {
            try {
                if (getStreamQueue(hash(offset)).offer(newStreamMessage, 60, TimeUnit.SECONDS)) {
                    break;
                } else {
                    logger.info("the queue is full, wait for builder to catch up");
                }
            } catch (InterruptedException e) {
                logger.info("InterruptedException", e);
                continue;
            }
        }
    }

    public void stop() {
        this.isRunning = false;
    }

}
