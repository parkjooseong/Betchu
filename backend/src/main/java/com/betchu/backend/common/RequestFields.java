package com.betchu.backend.common;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

public final class RequestFields {
  private RequestFields() {}

  public static void object(JsonNode value, String... keys) {
    if (value == null
        || !value.isObject()
        || value.size() != keys.length
        || Arrays.stream(keys).anyMatch(k -> !value.has(k))) throw invalid();
  }

  public static String text(JsonNode value, String key, int max) {
    var field = value.path(key);
    if (!field.isString() || field.asString().isBlank() || field.asString().length() > max)
      throw invalid();
    String result = field.asString().strip();
    if (result.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) throw invalid();
    return result;
  }

  public static String choice(JsonNode value, String key, String... options) {
    String result = text(value, key, 60);
    if (!Set.of(options).contains(result)) throw invalid();
    return result;
  }

  public static UUID uuid(String value) {
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw invalid();
      return id;
    } catch (IllegalArgumentException | NullPointerException error) {
      throw invalid();
    }
  }

  public static long revision(JsonNode value) {
    var field = value.path("rowVersion");
    if (!field.isIntegralNumber() || !field.canConvertToLong() || field.asLong() < 0)
      throw invalid();
    return field.asLong();
  }

  public static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해 주세요.");
  }
}
