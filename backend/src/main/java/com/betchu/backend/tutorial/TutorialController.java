package com.betchu.backend.tutorial;

import static com.betchu.backend.tutorial.TutorialModels.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tutorials")
public class TutorialController {
  private final TutorialService service;

  public TutorialController(TutorialService service) {
    this.service = service;
  }

  @GetMapping
  public Overview overview(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.overview(user.userId());
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public View create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CreateInput input) {
    return service.create(user.userId(), key(key), input);
  }

  @GetMapping("/{questId}")
  public View get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID questId) {
    return service.get(user.userId(), questId);
  }

  @PostMapping("/{questId}/approve")
  public View approve(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ApproveInput input) {
    return service.approve(user.userId(), questId, key(key), input);
  }

  @PostMapping("/{questId}/reject")
  public View reject(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody RejectInput input) {
    return service.reject(user.userId(), questId, key(key), input);
  }

  @PostMapping("/{questId}/select-result")
  public View select(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ResultInput input) {
    return service.selectResult(user.userId(), questId, key(key), input);
  }

  @PostMapping("/{questId}/final-approve")
  public View finalApprove(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ResultInput input) {
    return service.finalApprove(user.userId(), questId, key(key), input);
  }

  @PostMapping("/{questId}/reject-result")
  public View rejectResult(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody VersionInput input) {
    return service.rejectResult(user.userId(), questId, key(key), input);
  }

  @PostMapping("/{questId}/cancel")
  public View cancel(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody VersionInput input) {
    return service.cancel(user.userId(), questId, key(key), input);
  }

  @PostMapping("/{questId}/confirm-cancel")
  public View confirmCancel(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody VersionInput input) {
    return service.respondCancel(user.userId(), questId, key(key), input, true);
  }

  @PostMapping("/{questId}/reject-cancel")
  public View rejectCancel(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody VersionInput input) {
    return service.respondCancel(user.userId(), questId, key(key), input, false);
  }

  private UUID key(String input) {
    try {
      UUID value = UUID.fromString(input);
      if (!value.toString().equalsIgnoreCase(input)) throw new IllegalArgumentException();
      return value;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "유효한 요청 키가 필요해요.");
    }
  }
}
