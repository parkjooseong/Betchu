package com.betchu.backend.game;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.game.GameModels.*;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GameController {
  private final MonsterService monsters;
  private final HomeService home;

  public GameController(MonsterService monsters, HomeService home) {
    this.monsters = monsters;
    this.home = home;
  }

  @PostMapping("/monsters/starter")
  @ResponseStatus(HttpStatus.CREATED)
  public MonsterView create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody PreviewRequest request) {
    return monsters.create(user.userId(), key(key), request);
  }

  @GetMapping("/monsters/me")
  public MonsterResult me(@AuthenticationPrincipal AuthenticatedUser user) {
    return monsters.me(user.userId());
  }

  @GetMapping("/monsters/partner")
  public MonsterResult partner(@AuthenticationPrincipal AuthenticatedUser user) {
    return monsters.partner(user.userId());
  }

  @PatchMapping("/monsters/me/name")
  public MonsterView rename(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody RenameRequest request) {
    return monsters.rename(user.userId(), request.name());
  }

  @GetMapping("/home")
  public Home home(@AuthenticationPrincipal AuthenticatedUser user) {
    return home.home(user.userId());
  }

  private UUID key(String input) {
    try {
      UUID result = UUID.fromString(input);
      if (!result.toString().equalsIgnoreCase(input)) throw new IllegalArgumentException();
      return result;
    } catch (IllegalArgumentException | NullPointerException invalid) {
      throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", "유효한 요청 키가 필요해요.");
    }
  }
}
