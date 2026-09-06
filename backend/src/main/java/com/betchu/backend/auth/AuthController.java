package com.betchu.backend.auth;

import com.betchu.backend.auth.AuthModels.*;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final AuthService auth;
  private final AccountService accounts;
  private final PolicyService policies;

  public AuthController(AuthService auth, AccountService accounts, PolicyService policies) {
    this.auth = auth;
    this.accounts = accounts;
    this.policies = policies;
  }

  @GetMapping("/auth/providers")
  public Providers providers() {
    return auth.providers();
  }

  @PostMapping("/auth/login/start")
  public LoginStart start(@RequestBody StartRequest request) {
    return auth.start(request);
  }

  @GetMapping("/auth/oauth/google/callback")
  public ResponseEntity<Void> callback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error) {
    return ResponseEntity.status(302)
        .location(URI.create(auth.callback(state, code, error)))
        .cacheControl(CacheControl.noStore())
        .header("Referrer-Policy", "no-referrer")
        .build();
  }

  @PostMapping("/auth/login")
  public Tokens login(@RequestBody LoginRequest request) {
    return auth.login(request);
  }

  @PostMapping("/auth/refresh")
  public Tokens refresh(@RequestBody RefreshRequest request) {
    return auth.refresh(request.refreshToken());
  }

  @PostMapping("/auth/logout")
  public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthenticatedUser principal) {
    auth.logout(principal);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/users/me")
  public UserMe me(@AuthenticationPrincipal AuthenticatedUser principal) {
    return accounts.me(principal.userId());
  }

  @GetMapping("/policy-versions/current")
  public CurrentPolicies policies(@RequestParam(defaultValue = "ko-KR") String locale) {
    return policies.current(locale);
  }

  @PostMapping("/onboarding/age-eligibility")
  public ResponseEntity<UserMe> age(
      @AuthenticationPrincipal AuthenticatedUser principal, @RequestBody AgeRequest request) {
    UserMe user = accounts.age(principal.userId(), request.eligible());
    return user == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(user);
  }

  @PutMapping("/consents/{policyType}")
  public UserMe consent(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable String policyType,
      @RequestBody ConsentRequest request) {
    return accounts.consent(principal.userId(), policyType, request);
  }
}
