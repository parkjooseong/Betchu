package com.betchu.backend.auth;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AuthSettings(
    String clientId,
    String clientSecret,
    String callbackUri,
    List<String> redirectUris,
    String signingSecret) {
  @Autowired
  public AuthSettings(
      @Value("${betchu.auth.google.client-id:}") String clientId,
      @Value("${betchu.auth.google.client-secret:}") String clientSecret,
      @Value(
              "${betchu.auth.google.callback-uri:http://localhost:8080/api/v1/auth/oauth/google/callback}")
          String callbackUri,
      @Value(
              "${betchu.auth.redirect-uris:betchu://auth/callback,http://localhost:8081/auth/callback}")
          String redirectUris,
      @Value("${betchu.auth.signing-secret:}") String signingSecret) {
    this(
        clientId,
        clientSecret,
        callbackUri,
        Arrays.stream(redirectUris.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList(),
        signingSecret);
  }

  public boolean googleConfigured() {
    if (clientId.isBlank() || clientSecret.isBlank()) return false;
    URI callback = URI.create(callbackUri);
    return ("https".equals(callback.getScheme())
            || ("http".equals(callback.getScheme()) && "localhost".equals(callback.getHost())))
        && callback.getFragment() == null
        && callback.getQuery() == null;
  }

  @Override
  public String toString() {
    return "AuthSettings[credentials=redacted]";
  }
}
