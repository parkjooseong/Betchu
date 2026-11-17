package com.betchu.backend.config;

import java.net.URI;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Reject development defaults before a production instance can become ready. */
@Component
@Profile("production")
public class ProductionGuard implements InitializingBean {
  private final Environment environment;

  public ProductionGuard(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void afterPropertiesSet() {
    validate(environment);
  }

  static void validate(Environment env) {
    var errors = new ArrayList<String>();
    String db = value(env, "spring.datasource.url");
    if (!databaseTls(db)) errors.add("DB_URL must use PostgreSQL with sslmode=verify-full");
    required(env, errors, "spring.datasource.username");
    String password = value(env, "spring.datasource.password");
    if (password.length() < 16 || password.equals("betchu_local"))
      errors.add("DB_PASSWORD must be a dedicated production credential");
    var signing = secret(env, errors, "betchu.auth.signing-secret", false);
    var invite = secret(env, errors, "betchu.couples.invite-hmac-secret", false);
    var appleEncryption = secret(env, errors, "betchu.auth.apple.token-encryption-secret", true);
    if (Arrays.equals(signing, invite)
        || Arrays.equals(signing, appleEncryption)
        || Arrays.equals(invite, appleEncryption))
      errors.add("JWT, invitation and Apple encryption keys must be different");
    for (String key :
        List.of(
            "betchu.auth.google.client-id",
            "betchu.auth.google.client-secret",
            "betchu.auth.apple.client-id",
            "betchu.auth.apple.team-id",
            "betchu.auth.apple.key-id")) required(env, errors, key);
    https(env, errors, "betchu.auth.google.callback-uri", "/api/v1/auth/oauth/google/callback");
    https(env, errors, "betchu.auth.apple.callback-uri", "/api/v1/auth/oauth/apple/callback");
    String redirects = value(env, "betchu.auth.redirect-uris");
    if (redirects.isBlank()) errors.add("OAUTH_REDIRECT_URIS is required");
    for (String redirect : redirects.split(",")) {
      if (!redirect.trim().equals("betchu://auth/callback")
          && !publicHttps(redirect.trim(), "/auth/callback"))
        errors.add("OAUTH_REDIRECT_URIS contains an unsafe callback");
    }
    String origins = value(env, "betchu.cors.allowed-origins");
    for (String origin : origins.split(","))
      if (!origin.isBlank() && !publicHttps(origin.trim(), ""))
        errors.add("CORS_ALLOWED_ORIGINS must contain exact HTTPS origins");
    try {
      var key =
          (ECPrivateKey)
              KeyFactory.getInstance("EC")
                  .generatePrivate(
                      new PKCS8EncodedKeySpec(
                          Base64.getDecoder()
                              .decode(value(env, "betchu.auth.apple.private-key-base64"))));
      if (key.getParams().getCurve().getField().getFieldSize() != 256)
        throw new IllegalArgumentException();
    } catch (Exception error) {
      errors.add("APPLE_PRIVATE_KEY_BASE64 must contain a P-256 PKCS8 private key");
    }
    if (env.getProperty("betchu.notifications.enabled", Boolean.class, false))
      required(env, errors, "betchu.notifications.expo-access-token");
    if (!errors.isEmpty())
      throw new IllegalStateException(
          "Production configuration is incomplete: "
              + String.join("; ", new LinkedHashSet<>(errors)));
  }

  static boolean publicHttps(String input, String path) {
    try {
      URI uri = URI.create(input);
      String host = uri.getHost();
      return "https".equals(uri.getScheme())
          && host != null
          && host.contains(".")
          && !host.equals("localhost")
          && !host.endsWith(".localhost")
          && !host.endsWith(".local")
          && !host.endsWith(".test")
          && !host.endsWith(".invalid")
          && !host.equals("example.com")
          && !host.endsWith(".example.com")
          && !host.matches("[0-9.]+")
          && uri.getUserInfo() == null
          && uri.getFragment() == null
          && uri.getQuery() == null
          && uri.getPort() == -1
          && path.equals(uri.getPath());
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private static boolean databaseTls(String value) {
    try {
      if (!value.startsWith("jdbc:postgresql://")) return false;
      var uri = URI.create(value.substring(5));
      var modes =
          Arrays.stream(Objects.toString(uri.getRawQuery(), "").split("&"))
              .filter(part -> part.startsWith("sslmode="))
              .toList();
      return uri.getHost() != null
          && uri.getUserInfo() == null
          && uri.getFragment() == null
          && modes.equals(List.of("sslmode=verify-full"));
    } catch (IllegalArgumentException error) {
      return false;
    }
  }

  private static void https(Environment env, List<String> errors, String key, String path) {
    if (!publicHttps(value(env, key), path)) errors.add(key + " must be a public HTTPS callback");
  }

  private static String value(Environment env, String key) {
    return env.getProperty(key, "").trim();
  }

  private static void required(Environment env, List<String> errors, String key) {
    if (value(env, key).isBlank()) errors.add(key + " is required");
  }

  private static byte[] secret(Environment env, List<String> errors, String key, boolean exact) {
    try {
      byte[] bytes = Base64.getDecoder().decode(value(env, key));
      var distinct = new HashSet<Byte>();
      for (byte b : bytes) distinct.add(b);
      if (bytes.length < 32 || (exact && bytes.length != 32) || distinct.size() < 8)
        throw new IllegalArgumentException();
      return bytes;
    } catch (IllegalArgumentException error) {
      errors.add(key + " must be an independently generated Base64 key");
      return new byte[0];
    }
  }
}
