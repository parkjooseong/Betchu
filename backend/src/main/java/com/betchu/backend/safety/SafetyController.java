package com.betchu.backend.safety;

import static com.betchu.backend.safety.SafetyModels.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.RequestFields;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SafetyController {
  private final AccountSafetyService service;

  public SafetyController(AccountSafetyService service) {
    this.service = service;
  }

  @PostMapping("/reports")
  public ReportReceipt report(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody ReportInput input) {
    return service.report(user.userId(), RequestFields.uuid(key), input);
  }

  @GetMapping("/reports/targets")
  public java.util.List<AccountSafetyService.ReportTarget> targets(
      @AuthenticationPrincipal AuthenticatedUser user) {
    return service.reportTargets(user.userId());
  }

  @PostMapping("/account/delete")
  public Deletion delete(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @RequestBody DeleteInput input) {
    return service.delete(user, RequestFields.uuid(key), input);
  }

  @GetMapping("/account/export")
  public ExportPage export(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam String section,
      @RequestParam(required = false) String cursor) {
    return service.export(user.userId(), section, cursor);
  }
}
