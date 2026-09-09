package com.betchu.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class AuthSecrets {
  private static final SecureRandom RANDOM = new SecureRandom();

  private AuthSecrets() {}

  public static String randomToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hash(String input) {
    return HexFormat.of().formatHex(digest(input));
  }

  public static String challenge(String verifier) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
  }

  private static byte[] digest(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  public static boolean matches(String input, String hash) {
    return input != null
        && hash != null
        && MessageDigest.isEqual(
            hash(input).getBytes(StandardCharsets.US_ASCII),
            hash.getBytes(StandardCharsets.US_ASCII));
  }
}
