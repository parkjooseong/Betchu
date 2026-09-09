package com.betchu.backend.couples;

import static com.betchu.backend.couples.CoupleModels.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/couples")
public class CoupleController {
  private final CoupleService service;

  public CoupleController(CoupleService service) {
    this.service = service;
  }

  @GetMapping("/me")
  public State me(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.me(userId(user));
  }

  @PostMapping("/invites")
  public CreatedInvite create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    return service.create(userId(user), requestKey(key));
  }

  @DeleteMapping("/invites/{inviteId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID inviteId) {
    service.revoke(userId(user), inviteId);
  }

  @PostMapping("/invites/preview")
  public InvitePreview preview(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody CodeRequest request) {
    return service.preview(userId(user), request.code());
  }

  @PostMapping("/join")
  public PendingInvite join(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody CodeRequest request) {
    return service.join(userId(user), request.code());
  }

  @PostMapping("/pending/{inviteId}/confirm")
  public State confirm(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID inviteId) {
    return service.confirm(userId(user), inviteId);
  }

  @PostMapping("/pending/{inviteId}/reject")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reject(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID inviteId) {
    service.reject(userId(user), inviteId);
  }

  @PostMapping("/me/end")
  public EndResult end(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    return service.end(userId(user), requestKey(key), false);
  }

  @GetMapping("/me/end-status")
  public EndStatus endStatus(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.endStatus(userId(user));
  }

  @PostMapping("/me/block")
  public EndResult block(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    return service.end(userId(user), requestKey(key), true);
  }

  private static UUID userId(AuthenticatedUser user) {
    if (user == null)
      throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "로그인이 필요해요.");
    return user.userId();
  }

  private static UUID requestKey(String key) {
    try {
      UUID parsed = UUID.fromString(key);
      if (!parsed.toString().equalsIgnoreCase(key)) throw new IllegalArgumentException();
      return parsed;
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "유효한 요청 키가 필요해요.");
    }
  }
}
