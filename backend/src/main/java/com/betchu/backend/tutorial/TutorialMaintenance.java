package com.betchu.backend.tutorial;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TutorialMaintenance {
  private final TutorialService tutorials;

  public TutorialMaintenance(TutorialService tutorials) {
    this.tutorials = tutorials;
  }

  @Scheduled(
      fixedDelayString = "${betchu.tutorial.maintenance-delay-ms:5000}",
      initialDelayString = "${betchu.tutorial.maintenance-delay-ms:5000}")
  public void processDeadlines() {
    tutorials.processDue();
  }
}
