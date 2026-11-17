package com.betchu.backend.config;

import com.betchu.backend.auth.PolicyService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component("releasePolicies")
@Profile("production")
public class ReleasePoliciesHealthIndicator implements HealthIndicator {
  private final PolicyService policies;

  public ReleasePoliciesHealthIndicator(PolicyService policies) {
    this.policies = policies;
  }

  @Override
  public Health health() {
    try {
      var current = policies.current("ko-KR");
      boolean ready =
          current.ready()
              && current.policies().stream()
                  .allMatch(
                      policy -> {
                        try {
                          var uri = java.net.URI.create(policy.documentUrl());
                          return ProductionGuard.publicHttps(policy.documentUrl(), uri.getPath());
                        } catch (IllegalArgumentException error) {
                          return false;
                        }
                      });
      return ready ? Health.up().build() : Health.down().build();
    } catch (RuntimeException error) {
      return Health.down().build();
    }
  }
}
