package com.betchu.backend.auth;

import com.betchu.backend.common.ApiException;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

public final class AuthModels {
  private AuthModels() {}

  public record Provider(String provider, String displayName, boolean enabled) {}

  public record Providers(List<Provider> providers, boolean registrationAvailable) {}

  public record Policy(
      UUID id,
      String policyType,
      String version,
      String locale,
      boolean required,
      String documentUrl,
      Instant effectiveAt) {}

  public record CurrentPolicies(boolean ready, List<Policy> policies) {}

  public record UserMe(
      UUID id,
      String nickname,
      String status,
      boolean ageEligible,
      List<UUID> requiredPolicyVersionIds,
      List<UUID> missingPolicyVersionIds,
      boolean evidenceConsent,
      Long availableCoins,
      Long lockedCoins) {}

  public record LoginStart(
      UUID loginId, String loginSecret, String authorizationUrl, Instant expiresAt) {}

  public record Tokens(String accessToken, String refreshToken, long expiresIn, UserMe user) {}

  public record VerifiedIdentity(String subject, String nickname) {}

  public record StartRequest(String provider, String redirectUri) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static StartRequest parse(JsonNode input) {
      object(input, 2);
      return new StartRequest(text(input, "provider", 20), text(input, "redirectUri", 1000));
    }
  }

  public record LoginRequest(UUID loginId, String loginSecret, String handoffCode) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LoginRequest parse(JsonNode input) {
      object(input, 3);
      return new LoginRequest(
          uuid(input, "loginId"), text(input, "loginSecret", 128), text(input, "handoffCode", 128));
    }
  }

  public record RefreshRequest(String refreshToken) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RefreshRequest parse(JsonNode input) {
      object(input, 1);
      return new RefreshRequest(text(input, "refreshToken", 128));
    }
  }

  public record AgeRequest(Boolean eligible) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AgeRequest parse(JsonNode input) {
      object(input, 1);
      JsonNode value = input.path("eligible");
      if (!value.isBoolean() && !value.isNull()) throw invalid();
      return new AgeRequest(value.isNull() ? null : value.asBoolean());
    }
  }

  public record ConsentRequest(UUID policyVersionId, String locale) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ConsentRequest parse(JsonNode input) {
      object(input, 2);
      return new ConsentRequest(uuid(input, "policyVersionId"), text(input, "locale", 20));
    }
  }

  private static void object(JsonNode input, int size) {
    if (input == null || !input.isObject() || input.size() != size) throw invalid();
  }

  private static String text(JsonNode input, String key, int max) {
    JsonNode value = input.path(key);
    if (!value.isString() || value.asString().isBlank() || value.asString().length() > max)
      throw invalid();
    return value.asString();
  }

  private static UUID uuid(JsonNode input, String key) {
    String value = text(input, key, 36);
    try {
      UUID id = UUID.fromString(value);
      if (!id.toString().equalsIgnoreCase(value)) throw invalid();
      return id;
    } catch (IllegalArgumentException error) {
      throw invalid();
    }
  }

  private static ApiException invalid() {
    return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 형식을 확인해 주세요.");
  }
}
