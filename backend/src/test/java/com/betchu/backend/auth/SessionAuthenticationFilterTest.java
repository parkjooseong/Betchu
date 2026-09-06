package com.betchu.backend.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betchu.backend.common.ApiException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

class SessionAuthenticationFilterTest {
  private final AuthService auth = mock(AuthService.class);
  private final AccountService accounts = mock(AccountService.class);
  private final SessionAuthenticationFilter filter =
      new SessionAuthenticationFilter(auth, accounts, JsonMapper.builder().build());
  private final AuthenticatedUser principal =
      new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID());

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/api/v1/couples/me/end", "/api/v1/couples/me/block", "/api/v1/auth/logout"})
  void missingNewConsentDoesNotPreventRelationshipSafetyActions(String path) throws Exception {
    when(auth.authenticate("test-token")).thenReturn(principal);
    var request = new MockHttpServletRequest("POST", path);
    request.setServletPath(path);
    request.addHeader("Authorization", "Bearer test-token");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(chain.getRequest()).isSameAs(request);
    verifyNoInteractions(accounts);
  }

  @Test
  void checksCurrentPolicyConsentBeforeEveryGeneralRequest() throws Exception {
    when(auth.authenticate("test-token")).thenReturn(principal);
    doThrow(new ApiException(HttpStatus.FORBIDDEN, "ONBOARDING_REQUIRED", "동의 필요"))
        .when(accounts)
        .requireGeneralAccess(principal.userId());
    var request = new MockHttpServletRequest("GET", "/api/v1/couples/me");
    request.setServletPath("/api/v1/couples/me");
    request.addHeader("Authorization", "Bearer test-token");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(chain.getRequest()).isNull();
    assertThat(response.getContentAsString()).contains("ONBOARDING_REQUIRED");
  }

  @Test
  void revokedSessionCannotReachSafeRoutes() throws Exception {
    when(auth.authenticate("revoked"))
        .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "로그인 필요"));
    var request = new MockHttpServletRequest("POST", "/api/v1/auth/logout");
    request.setServletPath("/api/v1/auth/logout");
    request.addHeader("Authorization", "Bearer revoked");
    var response = new MockHttpServletResponse();
    var chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    assertThat(response.getStatus()).isEqualTo(401);
    assertThat(chain.getRequest()).isNull();
  }
}
