package com.betchu.backend.auth;

import com.betchu.backend.auth.AuthModels.VerifiedIdentity;
import com.betchu.backend.common.ApiException;
import java.net.http.HttpClient;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@Component
public class GoogleIdentityClient {
  private final AuthSettings settings;
  private final RestClient client;
  private final JwtDecoder decoder;

  public GoogleIdentityClient(AuthSettings settings, Clock clock) {
    this.settings = settings;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    client = RestClient.builder().requestFactory(factory).build();
    var jwtDecoder =
        NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs")
            .restOperations(new org.springframework.web.client.RestTemplate(factory))
            .build();
    var timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(30));
    timestampValidator.setClock(clock);
    jwtDecoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestampValidator, new GoogleTokenValidator(settings.clientId(), clock)));
    decoder = jwtDecoder;
  }

  public String authorizationUrl(String state, String nonce, String verifier) {
    return UriComponentsBuilder.fromUriString("https://accounts.google.com/o/oauth2/v2/auth")
        .queryParam("client_id", settings.clientId())
        .queryParam("redirect_uri", settings.callbackUri())
        .queryParam("response_type", "code")
        .queryParam("scope", "openid profile")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .queryParam("code_challenge", AuthSecrets.challenge(verifier))
        .queryParam("code_challenge_method", "S256")
        .queryParam("prompt", "select_account")
        .build()
        .encode()
        .toUriString();
  }

  public VerifiedIdentity exchange(String code, String verifier, String nonceHash) {
    if (!settings.googleConfigured()) throw SessionTokenService.unavailable();
    try {
      var form = new LinkedMultiValueMap<String, String>();
      form.add("client_id", settings.clientId());
      form.add("client_secret", settings.clientSecret());
      form.add("code", code);
      form.add("grant_type", "authorization_code");
      form.add("redirect_uri", settings.callbackUri());
      form.add("code_verifier", verifier);
      JsonNode response =
          client
              .post()
              .uri("https://oauth2.googleapis.com/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (response == null || !response.path("id_token").isString()) throw invalidIdentity();
      Jwt identity = decoder.decode(response.path("id_token").asString());
      if (!GoogleTokenValidator.validNonce(identity, nonceHash)
          || identity.getSubject() == null
          || identity.getSubject().isBlank()
          || identity.getSubject().length() > 255) throw invalidIdentity();
      return new VerifiedIdentity(
          identity.getSubject(), nickname(identity.getClaimAsString("name")));
    } catch (RuntimeException error) {
      throw invalidIdentity();
    }
  }

  private String nickname(String name) {
    if (name == null) return "배츄";
    String clean =
        Normalizer.normalize(name, Normalizer.Form.NFC)
            .replaceAll("[\\p{C}\\p{Zl}\\p{Zp}]", "")
            .strip();
    if (clean.isBlank()) return "배츄";
    int count = clean.codePointCount(0, clean.length());
    return count > 30 ? clean.substring(0, clean.offsetByCodePoints(0, 30)) : clean;
  }

  private ApiException invalidIdentity() {
    return new ApiException(
        HttpStatus.UNAUTHORIZED, "OAUTH_FAILED", "Google 로그인 확인에 실패했어요. 다시 시도해 주세요.");
  }
}
