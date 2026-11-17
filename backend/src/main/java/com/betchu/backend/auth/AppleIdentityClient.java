package com.betchu.backend.auth;

import com.betchu.backend.auth.AuthModels.VerifiedIdentity;
import com.betchu.backend.common.ApiException;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.*;
import java.net.http.HttpClient;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@Component
public class AppleIdentityClient {
  private final AppleSettings settings;
  private final ProviderTokenCipher cipher;
  private final Clock clock;
  private final RestClient client;
  private final JwtDecoder decoder;

  public AppleIdentityClient(AppleSettings settings, ProviderTokenCipher cipher, Clock clock) {
    this.settings = settings;
    this.cipher = cipher;
    this.clock = clock;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(10));
    client = RestClient.builder().requestFactory(factory).build();
    var jwt =
        NimbusJwtDecoder.withJwkSetUri("https://appleid.apple.com/auth/keys")
            .restOperations(new org.springframework.web.client.RestTemplate(factory))
            .build();
    var timestamp = new JwtTimestampValidator(Duration.ofSeconds(30));
    timestamp.setClock(clock);
    jwt.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            timestamp, new AppleTokenValidator(settings.clientId(), clock)));
    decoder = jwt;
  }

  public String authorizationUrl(String state, String nonce) {
    return UriComponentsBuilder.fromUriString("https://appleid.apple.com/auth/authorize")
        .queryParam("client_id", settings.clientId())
        .queryParam("redirect_uri", settings.callbackUri())
        .queryParam("response_type", "code")
        .queryParam("response_mode", "form_post")
        .queryParam("state", state)
        .queryParam("nonce", nonce)
        .build()
        .encode()
        .toUriString();
  }

  public VerifiedIdentity exchange(String code, String nonceHash) {
    if (!settings.configured()) throw SessionTokenService.unavailable();
    try {
      var form = credentials();
      form.add("grant_type", "authorization_code");
      form.add("code", code);
      form.add("redirect_uri", settings.callbackUri());
      JsonNode response =
          client
              .post()
              .uri("https://appleid.apple.com/auth/token")
              .contentType(MediaType.APPLICATION_FORM_URLENCODED)
              .body(form)
              .retrieve()
              .body(JsonNode.class);
      if (response == null
          || !response.path("id_token").isString()
          || !response.path("refresh_token").isString()) throw new IllegalStateException();
      Jwt jwt = decoder.decode(response.path("id_token").asString());
      if (!AuthSecrets.matches(jwt.getClaimAsString("nonce"), nonceHash))
        throw new IllegalStateException();
      return new VerifiedIdentity(
          "APPLE",
          jwt.getSubject(),
          "배츄",
          cipher.encrypt(response.path("refresh_token").asString()));
    } catch (RuntimeException error) {
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "OAUTH_FAILED", "Apple 로그인 확인에 실패했어요. 다시 시도해 주세요.");
    }
  }

  public void revoke(String encryptedToken) {
    if (!settings.configured()) throw SessionTokenService.unavailable();
    var form = credentials();
    form.add("token", cipher.decrypt(encryptedToken));
    form.add("token_type_hint", "refresh_token");
    try {
      client
          .post()
          .uri("https://appleid.apple.com/auth/revoke")
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(form)
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException error) {
      throw new IllegalStateException("Apple revocation must be retried");
    }
  }

  private LinkedMultiValueMap<String, String> credentials() {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("client_id", settings.clientId());
    form.add("client_secret", clientSecret());
    return form;
  }

  String clientSecret() {
    try {
      var privateKey =
          (ECPrivateKey)
              KeyFactory.getInstance("EC")
                  .generatePrivate(
                      new PKCS8EncodedKeySpec(Base64.getDecoder().decode(settings.privateKey())));
      var jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(settings.keyId()).build(),
              new JWTClaimsSet.Builder()
                  .issuer(settings.teamId())
                  .subject(settings.clientId())
                  .audience("https://appleid.apple.com")
                  .issueTime(Date.from(clock.instant()))
                  .expirationTime(Date.from(clock.instant().plusSeconds(300)))
                  .build());
      jwt.sign(new ECDSASigner(privateKey));
      return jwt.serialize();
    } catch (Exception error) {
      throw new IllegalStateException("Apple signing configuration is invalid");
    }
  }
}
