package com.betchu.backend.gameplay;

import static com.betchu.backend.gameplay.EconomyModels.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.RequestFields;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GameplayController {
  private final EconomyService economy;
  private final WeeklyBossService weeks;

  public GameplayController(EconomyService economy, WeeklyBossService weeks) {
    this.economy = economy;
    this.weeks = weeks;
  }

  @GetMapping("/economy")
  public Economy get(@AuthenticationPrincipal AuthenticatedUser user) {
    return economy.get(user.userId());
  }

  @GetMapping("/wallet/history")
  public WalletHistory history(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID cursor) {
    return economy.history(user.userId(), cursor);
  }

  @PostMapping("/draws")
  public Draw draw(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody DrawInput input) {
    return economy.draw(user.userId(), RequestFields.uuid(key), input);
  }

  @PutMapping("/equipment")
  public Economy equip(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody EquipInput input) {
    return economy.equip(user.userId(), RequestFields.uuid(key), input);
  }

  @GetMapping("/rescue")
  public RescueStatus rescue(@AuthenticationPrincipal AuthenticatedUser user) {
    return economy.rescue(user.userId());
  }

  @PostMapping("/rescue/start")
  public RescueStatus start(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key) {
    return economy.startRescue(user.userId(), RequestFields.uuid(key));
  }

  @PostMapping("/rescue/complete")
  public RescueStatus complete(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody CompleteRescue input) {
    return economy.completeRescue(user.userId(), RequestFields.uuid(key), input);
  }

  @GetMapping("/coop/week")
  public WeeklyBossService.Week week(@AuthenticationPrincipal AuthenticatedUser user) {
    return weeks.get(user.userId());
  }

  @PostMapping("/coop/join")
  public WeeklyBossService.Week join(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody JoinInput input) {
    return weeks.join(user.userId(), RequestFields.uuid(key), input.bossId(), input.rowVersion());
  }
}
