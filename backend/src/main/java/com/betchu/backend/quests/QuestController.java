package com.betchu.backend.quests;

import static com.betchu.backend.quests.QuestModels.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quests")
public class QuestController {
  private final QuestService service;

  public QuestController(QuestService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public DraftView create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody DraftInput input) {
    return service.create(userId(user), key(key), input);
  }

  @GetMapping
  public DraftPage list(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(defaultValue = "DRAFT") String status,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor) {
    return service.list(userId(user), status, limit, cursor);
  }

  @GetMapping("/{questId}")
  public DraftView get(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID questId) {
    return service.get(userId(user), questId);
  }

  @PatchMapping("/{questId}/draft")
  public DraftView update(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestBody UpdateInput input) {
    return service.update(userId(user), questId, input.expectedRowVersion(), input.draft());
  }

  @DeleteMapping("/{questId}/draft")
  public DiscardResult discard(
      @AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID questId,
      @RequestParam long expectedRowVersion,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    return service.discard(userId(user), questId, expectedRowVersion, key(key));
  }

  private static UUID userId(AuthenticatedUser user) {
    if (user == null)
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
    return user.userId();
  }

  private static UUID key(String key) {
    try {
      UUID id = UUID.fromString(key);
      if (!id.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
      return id;
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "유효한 요청 키가 필요해요.");
    }
  }
}
