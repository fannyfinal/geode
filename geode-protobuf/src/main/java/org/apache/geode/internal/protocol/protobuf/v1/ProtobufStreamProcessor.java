/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.protocol.protobuf.v1;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import net.jpountz.lz4.LZ4FrameInputStream;
import net.jpountz.lz4.LZ4FrameOutputStream;
import net.jpountz.lz4.LZ4FrameOutputStream.BLOCKSIZE;
import org.apache.logging.log4j.Logger;

import org.apache.geode.annotations.Experimental;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.protocol.protobuf.statistics.ClientStatistics;
import org.apache.geode.internal.protocol.protobuf.v1.registry.ProtobufOperationContextRegistry;
import org.apache.geode.internal.protocol.protobuf.v1.serializer.ProtobufProtocolSerializer;
import org.apache.geode.internal.protocol.protobuf.v1.serializer.exception.InvalidProtocolMessageException;
import org.apache.geode.internal.protocol.protobuf.v1.state.AcceptMessages;

/**
 * This object handles an incoming stream containing protobuf messages. It parses the protobuf
 * messages, hands the requests to an appropriate handler, wraps the response in a protobuf message,
 * and then pushes it to the output stream.
 */
@Experimental
public class ProtobufStreamProcessor {
  public static final boolean USE_LZ4 = Boolean.getBoolean("gemfire.PROTOBUF_USE_LZ4");
  private final ProtobufProtocolSerializer protobufProtocolSerializer;
  private final ProtobufOpsProcessor protobufOpsProcessor;
  private static final Logger logger = LogService.getLogger();


  public ProtobufStreamProcessor() {
    protobufProtocolSerializer = new ProtobufProtocolSerializer();
    protobufOpsProcessor = new ProtobufOpsProcessor(new ProtobufOperationContextRegistry());
  }

  public void receiveMessage(InputStream inputStream, OutputStream outputStream,
      MessageExecutionContext executionContext) throws IOException {
    if (executionContext.getCompressionInputStream() != null) {
      inputStream = executionContext.getCompressionInputStream();
      outputStream = executionContext.getCompressionOutputStream();
    }
    try {
      processOneMessage(inputStream, outputStream, executionContext);
    } catch (InvalidProtocolMessageException e) {
      logger.info("Invalid message", e);
      throw new IOException(e);
    }

  }

  private void processOneMessage(InputStream inputStream, OutputStream outputStream,
      MessageExecutionContext executionContext)
      throws InvalidProtocolMessageException, IOException {
    if (executionContext.getConnectionState().handleMessageIndependently(inputStream, outputStream,
        executionContext)) {
      return;
    }

    ClientProtocol.Message message = protobufProtocolSerializer.deserialize(inputStream);
    if (message == null) {
      String errorMessage = "Tried to deserialize protobuf message at EOF";
      logger.debug(errorMessage);
      throw new EOFException(errorMessage);
    }
    ClientStatistics statistics = executionContext.getStatistics();
    statistics.messageReceived(message.getSerializedSize());

    ClientProtocol.Message response = protobufOpsProcessor.process(message, executionContext);
    statistics.messageSent(response.getSerializedSize());
    protobufProtocolSerializer.serialize(response, outputStream);
    outputStream.flush();

    if (message.hasHandshakeRequest() && executionContext.getCompressionInputStream() == null
        && (executionContext.getConnectionState() instanceof AcceptMessages) && USE_LZ4
        && !(executionContext instanceof LocatorMessageExecutionContext)) {
      executionContext
          .setCompressionOutputStream(new LZ4FrameOutputStream(outputStream, BLOCKSIZE.SIZE_64KB));
      executionContext.setCompressionInputStream(new LZ4FrameInputStream(inputStream));
    }
  }
}
