package com.betchu.backend.couples;

import com.betchu.backend.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class InviteCodeCodec {
  private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private final SecureRandom random = new SecureRandom();
  private final byte[] key;

  public InviteCodeCodec(@Value("${betchu.couples.invite-hmac-secret:}") String secret) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(secret);
    } catch (IllegalArgumentException exception) {
      decoded = new byte[0];
    }
    key = decoded.length >= 32 ? decoded : null;
  }

  public String generate() {
    requireConfigured();
    StringBuilder result = new StringBuilder(19);
    for (int index = 0; index < 16; index++) {
      if (index > 0 && index % 4 == 0) result.append('-');
      result.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return result.toString();
  }

  public String hash(String input) {
    requireConfigured();
    if (input == null || input.length() > 64) throw invalidCode();
    String normalized = input.strip().replace("-", "").toUpperCase(Locale.ROOT);
    if (normalized.length() != 16
        || normalized.chars().anyMatch(character -> ALPHABET.indexOf(character) < 0)) {
      throw invalidCode();
    }
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.US_ASCII)));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Invite code hashing is unavailable", exception);
    }
  }

  static ApiException invalidCode() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "INVALID_INVITE", "사용할 수 없는 초대 코드예요. 코드를 다시 확인해 주세요.");
  }

  private void requireConfigured() {
    if (key == null) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "COUPLE_INVITES_UNAVAILABLE",
          "초대 코드 기능을 준비 중이에요. 잠시 후 다시 시도해 주세요.");
    }
  }
}
