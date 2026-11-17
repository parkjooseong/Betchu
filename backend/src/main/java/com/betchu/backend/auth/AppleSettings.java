package com.betchu.backend.auth;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record AppleSettings(
    @Value("${betchu.auth.apple.client-id:}") String clientId,
    @Value("${betchu.auth.apple.team-id:}") String teamId,
    @Value("${betchu.auth.apple.key-id:}") String keyId,
    @Value("${betchu.auth.apple.private-key-base64:}") String privateKey,
    @Value("${betchu.auth.apple.callback-uri:}") String callbackUri,
    @Value("${betchu.auth.apple.token-encryption-secret:}") String encryptionSecret) {
  public boolean configured() {
    if (clientId.isBlank()
        || teamId.isBlank()
        || keyId.isBlank()
        || privateKey.isBlank()
        || encryptionSecret.isBlank()) return false;
    try {
      var uri = URI.create(callbackUri);
      return "https".equals(uri.getScheme())
          && uri.getHost() != null
          && uri.getQuery() == null
          && uri.getFragment() == null;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  @Override
  public String toString() {
    return "AppleSettings[credentials=redacted]";
  }
}
