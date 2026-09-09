package com.betchu.backend.quests;

import static com.betchu.backend.quests.QuestLifecycleModels.*;

import com.betchu.backend.auth.AuthenticatedUser;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class QuestLifecycleController {
  private final QuestLifecycleService service;
  private final QuestBettingService betting;

  public QuestLifecycleController(QuestLifecycleService service, QuestBettingService betting) {
    this.service = service;
    this.betting = betting;
  }

  @GetMapping("/quests/{questId}/progress")
  public View get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID questId) {
    return service.get(user.userId(), questId);
  }

  @GetMapping("/quests/{questId}/versions")
  public Versions versions(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID questId) {
    return service.versions(user.userId(), questId);
  }

  @GetMapping("/quests/{questId}/approval-quote")
  public Quote quote(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID questId) {
    return service.quote(user.userId(), questId);
  }

  @GetMapping("/actions/pending")
  public Page pending(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.pending(user.userId());
  }

  @GetMapping("/quests/summary")
  public ActivitySummary summary(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.summary(user.userId());
  }

  @PostMapping("/quests/{questId}/submit")
  public View submit(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody RowInput input) {
    return service.submit(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/recall")
  public View recall(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody VersionInput input) {
    return service.recall(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/request-change")
  public View requestChange(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ChangeInput input) {
    return service.requestChange(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/reject")
  public View reject(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody VersionInput input) {
    return service.reject(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/approve")
  public View approve(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ApproveInput input) {
    return service.approve(user.userId(), questId, QuestController.key(key), input);
  }

  @PutMapping("/quests/{questId}/partner-result")
  public View select(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ResultInput input) {
    return service.select(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/partner-result/final-approve")
  public View finalApprove(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody FinalInput input) {
    return service.finalApprove(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/partner-result/reject")
  public View rejectResult(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody RowInput input) {
    return service.rejectResult(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/cancel")
  public View cancel(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody RowInput input) {
    return service.cancel(user.userId(), questId, QuestController.key(key), input);
  }

  @PostMapping("/quests/{questId}/confirm-cancel")
  public View confirmCancel(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody RowInput input) {
    return service.respondCancel(user.userId(), questId, QuestController.key(key), input, true);
  }

  @PostMapping("/quests/{questId}/reject-cancel")
  public View rejectCancel(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody RowInput input) {
    return service.respondCancel(user.userId(), questId, QuestController.key(key), input, false);
  }

  @GetMapping("/couples/me/settlement-rules")
  public Risk risk(@AuthenticationPrincipal AuthenticatedUser user) {
    return betting.get(user.userId());
  }

  @PostMapping("/couples/me/settlement-rules/acknowledge")
  public Risk acknowledge(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody AcknowledgeInput input) {
    return betting.acknowledge(user.userId(), QuestController.key(key), input);
  }
}
