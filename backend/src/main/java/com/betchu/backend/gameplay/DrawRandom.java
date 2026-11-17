package com.betchu.backend.gameplay;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

@Component
public class DrawRandom {
  private final SecureRandom random = new SecureRandom();

  public int nextInt(int bound) {
    return random.nextInt(bound);
  }
}
