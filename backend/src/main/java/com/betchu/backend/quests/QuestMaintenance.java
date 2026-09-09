package com.betchu.backend.quests;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QuestMaintenance {
  private final QuestLifecycleService service;

  public QuestMaintenance(QuestLifecycleService service) {
    this.service = service;
  }

  @Scheduled(
      fixedDelayString = "${betchu.quests.maintenance-delay-ms:5000}",
      initialDelayString = "${betchu.quests.maintenance-delay-ms:5000}")
  public void processDeadlines() {
    service.processDue();
  }
}
