/*
 * Copyright (C) 2023 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wiremock.grpc;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.wiremock.grpc.dsl.WireMockGrpc.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.wiremock.grpc.GrpcExtensionFactory;
import org.wiremock.grpc.dsl.WireMockGrpcService;

import com.example.grpc.GreetingServiceGrpc;
import com.example.grpc.HelloRequest;
import com.example.grpc.HelloResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import wiremock.grpc.client.GreetingsClient;

public class GrpcTest {

  WireMockGrpcService mockGreetingService;
  ManagedChannel channel;
  GreetingsClient greetingsClient;

  static WireMockServer wm = new WireMockServer(wireMockConfig()
      .dynamicPort()
      .withRootDirectory("src/test/resources/wiremock")
      .extensions(new GrpcExtensionFactory())
  );

  @BeforeAll
  static void beforeAll() {
    wm.start();
  }

  @BeforeEach
  void init() {
    mockGreetingService =
        new WireMockGrpcService(new WireMock(wm.port()), GreetingServiceGrpc.SERVICE_NAME);

    channel = ManagedChannelBuilder.forAddress("localhost", wm.port()).usePlaintext().build();
    greetingsClient = new GreetingsClient(channel);
  }

  @AfterEach
  void tearDown() {
    channel.shutdown();
  }

  @AfterAll
  static void afterAll() {
    wm.stop();
  }

  @Test
  void dynamic_response_via_JSON() {
    mockGreetingService.stubFor(
        method("greeting")
            .willReturn(
                jsonTemplate(
                    "{\n"
                        + "    \"greeting\": \"Hello {{jsonPath request.body '$.name'}}\"\n"
                        + "}")));

    String greeting = greetingsClient.greet("Tom");

    assertThat(greeting, is("Hello Tom"));
  }

  @Test
  void response_from_message() {
    mockGreetingService.stubFor(
        method("greeting")
            .willReturn(message(HelloResponse.newBuilder().setGreeting("Hi Tom from object"))));

    String greeting = greetingsClient.greet("Whatever");

    assertThat(greeting, is("Hi Tom from object"));
  }

  @Test
  void request_matching_with_JSON() {
    mockGreetingService.stubFor(
        method("greeting")
            .withRequestMessage(equalToJson("{ \"name\":  \"Tom\" }"))
            .willReturn(message(HelloResponse.newBuilder().setGreeting("OK"))));

    assertThat(greetingsClient.greet("Tom"), is("OK"));

    assertThrows(StatusRuntimeException.class, () -> greetingsClient.greet("Wrong"));
  }

  @Test
  void request_matching_with_message() {
    mockGreetingService.stubFor(
        method("greeting")
            .withRequestMessage(equalToMessage(HelloRequest.newBuilder().setName("Tom")))
            .willReturn(message(HelloResponse.newBuilder().setGreeting("OK"))));

    assertThat(greetingsClient.greet("Tom"), is("OK"));

    StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class, () -> greetingsClient.greet("Wrong"));
    assertThat(
        exception.getMessage(), is("NOT_FOUND: No matching stub mapping found for gRPC request"));
  }

  @Test
  void non_OK_status() {
    mockGreetingService.stubFor(
        method("greeting").willReturn(Status.FAILED_PRECONDITION, "Failed some blah prerequisite"));

    StatusRuntimeException exception =
        assertThrows(StatusRuntimeException.class, () -> greetingsClient.greet("Whatever"));
    assertThat(exception.getMessage(), is("FAILED_PRECONDITION: Failed some blah prerequisite"));
  }

  @Test
  void streaming_request_unary_response() {
    mockGreetingService.stubFor(
        method("manyGreetingsOneReply")
            .withRequestMessage(equalToMessage(HelloRequest.newBuilder().setName("Rob").build()))
            .willReturn(message(HelloResponse.newBuilder().setGreeting("Hi Rob"))));

    assertThat(greetingsClient.manyGreetingsOneReply("Tom", "Uri", "Rob", "Mark"), is("Hi Rob"));
  }

  @Test
  void unary_request_streaming_response() {
    mockGreetingService.stubFor(
        method("oneGreetingManyReplies")
            .willReturn(message(HelloResponse.newBuilder().setGreeting("Hi Tom"))));

    assertThat(greetingsClient.oneGreetingManyReplies("Tom"), hasItem("Hi Tom"));
  }
}
