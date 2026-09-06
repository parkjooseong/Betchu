package com.betchu.backend.couples;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betchu.backend.auth.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

class RelationshipEndStatusAccessTest {
  @Test
  void cleanupStatusRemainsAvailableDuringPolicyReconsent() throws Exception {
    AuthService auth = mock(AuthService.class);
    AccountService accounts = mock(AccountService.class);
    when(auth.authenticate("test"))
        .thenReturn(new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID()));
    var filter = new SessionAuthenticationFilter(auth, accounts, JsonMapper.builder().build());
    var request = new MockHttpServletRequest("GET", "/api/v1/couples/me/end-status");
    request.setServletPath("/api/v1/couples/me/end-status");
    request.addHeader("Authorization", "Bearer test");
    var chain = new MockFilterChain();
    try {
      filter.doFilter(request, new MockHttpServletResponse(), chain);
      assertThat(chain.getRequest()).isSameAs(request);
      verifyNoInteractions(accounts);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
