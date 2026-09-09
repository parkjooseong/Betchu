package com.betchu.backend.couples;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
public class CoupleMaintenance {
  private final CoupleService service;

  public CoupleMaintenance(CoupleService service) {
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${betchu.couples.expiry-delay-ms:60000}",
      initialDelayString = "${betchu.couples.expiry-delay-ms:60000}")
  public void expireInvites() {
    service.expireInvites();
  }
}
