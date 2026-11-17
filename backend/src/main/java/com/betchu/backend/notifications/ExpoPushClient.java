package com.betchu.backend.notifications;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

@Component
public class ExpoPushClient {
  public record Outcome(String status, String ticketId) {}

  private final RestClient client;

  public ExpoPushClient(@Value("${betchu.notifications.expo-access-token:}") String token) {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(7));
    var builder =
        RestClient.builder().baseUrl("https://exp.host/--/api/v2/push").requestFactory(factory);
    if (!token.isBlank()) builder.defaultHeader("Authorization", "Bearer " + token);
    client = builder.build();
  }

  public Outcome send(String token, UUID notification) {
    try {
      var response =
          client
              .post()
              .uri("/send")
              .contentType(MediaType.APPLICATION_JSON)
              .body(
                  Map.of(
                      "to",
                      token,
                      "title",
                      "BETCHU",
                      "body",
                      "BETCHU에서 확인할 내용이 있어요. 앱을 열어 확인해 주세요.",
                      "data",
                      Map.of("notificationId", notification),
                      "channelId",
                      "activity",
                      "sound",
                      "default"))
              .retrieve()
              .body(JsonNode.class);
      return outcome(response == null ? null : response.path("data"), true);
    } catch (RestClientResponseException ex) {
      return new Outcome(
          ex.getStatusCode().value() == 429 || ex.getStatusCode().is5xxServerError()
              ? "RETRY"
              : "FAILED",
          null);
    } catch (RuntimeException ex) {
      return new Outcome("RETRY", null);
    }
  }

  public Outcome receipt(String ticket) {
    try {
      var response =
          client
              .post()
              .uri("/getReceipts")
              .contentType(MediaType.APPLICATION_JSON)
              .body(Map.of("ids", List.of(ticket)))
              .retrieve()
              .body(JsonNode.class);
      return outcome(response == null ? null : response.path("data").path(ticket), false);
    } catch (RuntimeException ex) {
      return new Outcome("RETRY", null);
    }
  }

  private Outcome outcome(JsonNode value, boolean ticket) {
    if (value == null || !value.isObject() || !value.path("status").isString())
      return new Outcome("RETRY", null);
    if ("ok".equals(value.path("status").asString())) {
      if (ticket && (!value.path("id").isString() || value.path("id").asString().length() > 100))
        return new Outcome("RETRY", null);
      return new Outcome(
          ticket ? "ACCEPTED" : "PROVIDER_ACCEPTED", ticket ? value.path("id").asString() : null);
    }
    String error = value.path("details").path("error").asString("");
    return new Outcome(
        "DeviceNotRegistered".equals(error)
            ? "DEVICE_GONE"
            : "MessageRateExceeded".equals(error) ? "RETRY" : "FAILED",
        null);
  }
}
