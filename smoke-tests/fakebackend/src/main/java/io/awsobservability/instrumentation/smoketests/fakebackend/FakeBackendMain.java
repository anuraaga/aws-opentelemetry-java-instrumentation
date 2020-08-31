/*
 * Copyright Amazon.com, Inc. or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package io.awsobservability.instrumentation.smoketests.fakebackend;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.module.SimpleSerializers;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import io.netty.buffer.ByteBufOutputStream;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.io.OutputStream;
import org.curioswitch.common.protobuf.json.MessageMarshaller;

public class FakeBackendMain {

  private static final JsonMapper OBJECT_MAPPER;

  static {
    var marshaller =
        MessageMarshaller.builder()
            .register(ExportTraceServiceRequest.getDefaultInstance())
            .build();

    var mapper = JsonMapper.builder();
    var module = new SimpleModule();
    var serializers = new SimpleSerializers();
    serializers.addSerializer(
        new StdSerializer<>(ExportTraceServiceRequest.class) {
          @Override
          public void serialize(
              ExportTraceServiceRequest value, JsonGenerator gen, SerializerProvider provider)
              throws IOException {
            marshaller.writeValue(value, gen);
          }
        });
    module.setSerializers(serializers);
    mapper.addModule(module);
    OBJECT_MAPPER = mapper.build();
  }

  public static void main(String[] args) {
    var collector = new FakeCollectorService();
    var server =
        Server.builder()
            .http(8080)
            .service(GrpcService.builder().addService(collector).build())
            .service(
                "/clear-requests",
                (ctx, req) -> {
                  collector.clearRequests();
                  return HttpResponse.of(HttpStatus.OK);
                })
            .service(
                "/get-requests",
                (ctx, req) -> {
                  var requests = collector.getRequests();
                  var buf = new ByteBufOutputStream(ctx.alloc().buffer());
                  OBJECT_MAPPER.writeValue((OutputStream) buf, requests);
                  return HttpResponse.of(
                      HttpStatus.OK, MediaType.JSON, HttpData.wrap(buf.buffer()));
                })
            .service("/health", HealthCheckService.of())
            .build();

    server.start().join();
    Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop().join()));
  }
}
