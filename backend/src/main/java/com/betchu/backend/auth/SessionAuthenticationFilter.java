package com.betchu.backend.auth;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.ApiExceptionHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

public class SessionAuthenticationFilter extends OncePerRequestFilter {
  private final AuthService auth;
  private final AccountService accounts;
  private final JsonMapper mapper;

  public SessionAuthenticationFilter(AuthService auth, AccountService accounts, JsonMapper mapper) {
    this.auth = auth;
    this.accounts = accounts;
    this.mapper = mapper;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    if (authorization != null) {
      try {
        if (!authorization.startsWith("Bearer ") || authorization.length() > 8192)
          throw SessionTokenService.unauthorized();
        AuthenticatedUser principal = auth.authenticate(authorization.substring(7));
        if (!limitedOrPublic(request)) accounts.requireGeneralAccess(principal.userId());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
            new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        SecurityContextHolder.setContext(context);
      } catch (ApiException error) {
        SecurityContextHolder.clearContext();
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiExceptionHandler.problem(error));
        return;
      }
    }
    chain.doFilter(request, response);
  }

  private boolean limitedOrPublic(HttpServletRequest request) {
    String path = request.getServletPath();
    String method = request.getMethod();
    if ("GET".equals(method)
        && Set.of(
                "/api/v1/users/me",
                "/api/v1/auth/providers",
                "/api/v1/policy-versions/current",
                "/api/v1/monsters/starters",
                "/api/v1/system/health")
            .contains(path)) return true;
    if ("POST".equals(method)
        && Set.of(
                "/api/v1/onboarding/age-eligibility",
                "/api/v1/auth/logout",
                "/api/v1/monsters/starter-preview",
                "/api/v1/auth/login/start",
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/couples/me/end",
                "/api/v1/couples/me/block")
            .contains(path)) return true;
    return "PUT".equals(method)
        && Set.of(
                "/api/v1/consents/TERMS",
                "/api/v1/consents/PRIVACY",
                "/api/v1/consents/EVIDENCE_OPTIONAL")
            .contains(path);
  }
}
