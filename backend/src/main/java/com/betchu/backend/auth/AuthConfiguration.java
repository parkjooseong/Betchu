package com.betchu.backend.auth;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableScheduling
public class AuthConfiguration {
  @Bean
  Clock authClock() {
    return Clock.systemUTC();
  }

  @Bean
  SessionAuthenticationFilter sessionAuthenticationFilter(
      AuthService auth, AccountService accounts, JsonMapper mapper) {
    return new SessionAuthenticationFilter(auth, accounts, mapper);
  }
}
