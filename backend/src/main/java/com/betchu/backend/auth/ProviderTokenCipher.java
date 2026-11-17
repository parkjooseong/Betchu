package com.betchu.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class ProviderTokenCipher {
  private final byte[] key;

  public ProviderTokenCipher(AppleSettings settings) {
    key =
        settings.encryptionSecret().isBlank()
            ? null
            : Base64.getDecoder().decode(settings.encryptionSecret());
    if (key != null && key.length != 32)
      throw new IllegalStateException("Apple token encryption requires a 32-byte key");
  }

  public String encrypt(String token) {
    try {
      byte[] nonce = new byte[12];
      new SecureRandom().nextBytes(nonce);
      byte[] encrypted =
          cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(token.getBytes(StandardCharsets.UTF_8));
      byte[] bytes = Arrays.copyOf(nonce, nonce.length + encrypted.length);
      System.arraycopy(encrypted, 0, bytes, nonce.length, encrypted.length);
      return Base64.getEncoder().encodeToString(bytes);
    } catch (Exception ex) {
      throw new IllegalStateException("Provider token encryption failed");
    }
  }

  public String decrypt(String encrypted) {
    try {
      byte[] bytes = Base64.getDecoder().decode(encrypted);
      if (bytes.length < 29) throw new IllegalArgumentException();
      return new String(
          cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(bytes, 12))
              .doFinal(Arrays.copyOfRange(bytes, 12, bytes.length)),
          StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Provider token decryption failed");
    }
  }

  private Cipher cipher(int mode, byte[] nonce) throws Exception {
    if (key == null) throw new IllegalStateException("Provider token encryption is not configured");
    var cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
    cipher.updateAAD("BETCHU:APPLE:v1".getBytes(StandardCharsets.UTF_8));
    return cipher;
  }
}
