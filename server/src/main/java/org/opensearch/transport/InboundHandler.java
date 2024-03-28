/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.transport;

import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;

import java.io.IOException;

/**
 * Handler for inbound data
 *
 * @opensearch.internal
 */
public class InboundHandler {

    private final ThreadPool threadPool;
    private final OutboundHandler outboundHandler;
    private final NamedWriteableRegistry namedWriteableRegistry;
    private final TransportHandshaker handshaker;
    private final TransportKeepAlive keepAlive;
    private final Transport.ResponseHandlers responseHandlers;
    private final Transport.RequestHandlers requestHandlers;

    private volatile TransportMessageListener messageListener = TransportMessageListener.NOOP_LISTENER;

    private volatile long slowLogThresholdMs = Long.MAX_VALUE;

    private final Tracer tracer;

    InboundHandler(
        ThreadPool threadPool,
        OutboundHandler outboundHandler,
        NamedWriteableRegistry namedWriteableRegistry,
        TransportHandshaker handshaker,
        TransportKeepAlive keepAlive,
        Transport.RequestHandlers requestHandlers,
        Transport.ResponseHandlers responseHandlers,
        Tracer tracer
    ) {
        this.threadPool = threadPool;
        this.outboundHandler = outboundHandler;
        this.namedWriteableRegistry = namedWriteableRegistry;
        this.handshaker = handshaker;
        this.keepAlive = keepAlive;
        this.requestHandlers = requestHandlers;
        this.responseHandlers = responseHandlers;
        this.tracer = tracer;
    }

    void setMessageListener(TransportMessageListener listener) {
        if (messageListener == TransportMessageListener.NOOP_LISTENER) {
            messageListener = listener;
        } else {
            throw new IllegalStateException("Cannot set message listener twice");
        }
    }

    void setSlowLogThreshold(TimeValue slowLogThreshold) {
        this.slowLogThresholdMs = slowLogThreshold.getMillis();
    }

    void inboundMessage(TcpChannel channel, BaseInboundMessage message) throws Exception {
        final long startTime = threadPool.relativeTimeInMillis();
        channel.getChannelStats().markAccessed(startTime);
        messageReceivedFromPipeline(channel, message, startTime);
    }

    private void messageReceivedFromPipeline(TcpChannel channel, BaseInboundMessage message, long startTime) throws IOException {
        if ((message.getProtocol()).equals(BaseInboundMessage.PROTOBUF_PROTOCOL)) {
            // protobuf messages logic can be added here
        } else {
            InboundMessage inboundMessage = (InboundMessage) message;
            TransportLogger.logInboundMessage(channel, inboundMessage);
            if (inboundMessage.isPing()) {
                keepAlive.receiveKeepAlive(channel);
            } else {
                NativeMessageHandler nativeInboundHandler = new NativeMessageHandler(
                    threadPool,
                    outboundHandler,
                    namedWriteableRegistry,
                    handshaker,
                    requestHandlers,
                    responseHandlers,
                    tracer
                );
                nativeInboundHandler.messageReceived(channel, inboundMessage, startTime, slowLogThresholdMs, messageListener);
            }
        }
    }
}
