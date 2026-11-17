package com.betchu.backend.config;

import com.betchu.backend.auth.SessionAuthenticationFilter;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.ApiExceptionHandler;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      ObjectProvider<SessionAuthenticationFilter> filters,
      JsonMapper mapper,
      org.springframework.core.env.Environment environment)
      throws Exception {
    var filter = filters.getIfAvailable();
    if (filter != null) http.addFilterBefore(filter, AnonymousAuthenticationFilter.class);
    var limits =
        new ApiRequestLimitsFilter(
            java.time.Clock.systemUTC(),
            environment.getProperty("betchu.http.requests-per-minute", Integer.class, 600),
            environment.getProperty("betchu.http.auth-requests-per-minute", Integer.class, 60));
    if (filter != null) http.addFilterBefore(limits, SessionAuthenticationFilter.class);
    else http.addFilterBefore(limits, AnonymousAuthenticationFilter.class);
    return http.cors(Customizer.withDefaults())
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/system/health",
                        "/actuator/health",
                        "/actuator/health/**",
                        "/api/v1/monsters/starters",
                        "/api/v1/auth/providers",
                        "/api/v1/policy-versions/current",
                        "/api/v1/auth/oauth/google/callback")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/monsters/starter-preview",
                        "/api/v1/auth/login/start",
                        "/api/v1/auth/login",
                        "/api/v1/auth/oauth/apple/callback",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .requestMatchers("/actuator/**")
                    .denyAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            errors ->
                errors.authenticationEntryPoint(
                    (request, response, error) -> {
                      response.setStatus(401);
                      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                      mapper.writeValue(
                          response.getOutputStream(),
                          ApiExceptionHandler.problem(
                              new ApiException(
                                  HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED", "로그인이 필요해요.")));
                    }))
        .build();
  }

  @Bean
  @ConditionalOnBean(SessionAuthenticationFilter.class)
  FilterRegistrationBean<SessionAuthenticationFilter> sessionFilterRegistration(
      SessionAuthenticationFilter filter) {
    var registration = new FilterRegistrationBean<SessionAuthenticationFilter>();
    registration.setFilter(filter);
    registration.setEnabled(false);
    return registration;
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${betchu.cors.allowed-origins:http://localhost:8081,http://localhost:19006}")
          String allowedOrigins) {
    List<String> origins =
        Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList();
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/v1/monsters/starters", cors(origins, List.of("GET")));
    source.registerCorsConfiguration(
        "/api/v1/monsters/starter-preview", cors(origins, List.of("POST")));
    source.registerCorsConfiguration("/api/v1/auth/**", cors(origins, List.of("GET", "POST")));
    source.registerCorsConfiguration("/api/v1/users/me", cors(origins, List.of("GET")));
    source.registerCorsConfiguration(
        "/api/v1/policy-versions/current", cors(origins, List.of("GET")));
    source.registerCorsConfiguration(
        "/api/v1/onboarding/age-eligibility", cors(origins, List.of("POST")));
    source.registerCorsConfiguration("/api/v1/consents/*", cors(origins, List.of("PUT", "DELETE")));
    source.registerCorsConfiguration(
        "/api/v1/couples/**", cors(origins, List.of("GET", "POST", "DELETE")));
    source.registerCorsConfiguration(
        "/api/v1/quests/**", cors(origins, List.of("GET", "POST", "PUT", "PATCH", "DELETE")));
    source.registerCorsConfiguration("/api/v1/actions/pending", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/quests", cors(origins, List.of("GET", "POST")));
    source.registerCorsConfiguration("/api/v1/tutorials", cors(origins, List.of("GET", "POST")));
    source.registerCorsConfiguration("/api/v1/tutorials/**", cors(origins, List.of("GET", "POST")));
    source.registerCorsConfiguration("/api/v1/monsters/starter", cors(origins, List.of("POST")));
    source.registerCorsConfiguration("/api/v1/monsters/me", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/monsters/partner", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/monsters/me/name", cors(origins, List.of("PATCH")));
    source.registerCorsConfiguration("/api/v1/home", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/notifications", cors(origins, List.of("GET")));
    source.registerCorsConfiguration(
        "/api/v1/notifications/**", cors(origins, List.of("GET", "POST", "PUT", "DELETE")));
    source.registerCorsConfiguration("/api/v1/quest-templates", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/quest-templates/**", cors(origins, List.of("POST")));
    source.registerCorsConfiguration("/api/v1/account/**", cors(origins, List.of("GET", "POST")));
    source.registerCorsConfiguration("/api/v1/reports", cors(origins, List.of("POST")));
    source.registerCorsConfiguration("/api/v1/reports/targets", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/economy", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/wallet/**", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/draws", cors(origins, List.of("POST")));
    source.registerCorsConfiguration("/api/v1/equipment", cors(origins, List.of("PUT")));
    source.registerCorsConfiguration("/api/v1/rescue", cors(origins, List.of("GET")));
    source.registerCorsConfiguration("/api/v1/rescue/**", cors(origins, List.of("POST")));
    source.registerCorsConfiguration("/api/v1/coop/**", cors(origins, List.of("GET", "POST")));
    return source;
  }

  private CorsConfiguration cors(List<String> origins, List<String> methods) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(methods);
    config.setAllowedHeaders(List.of("Content-Type", "Authorization", "Idempotency-Key"));
    config.setAllowCredentials(false);
    config.setMaxAge(3600L);
    return config;
  }
}
