package com.betchu.backend.auth;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.betchu.backend.auth.AuthModels.CurrentPolicies;
import com.betchu.backend.auth.AuthModels.Provider;
import com.betchu.backend.auth.AuthModels.Providers;
import com.betchu.backend.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthController.class, properties = "betchu.security.enabled=false")
@Import(SecurityConfig.class)
class AuthControllerTest {
  @Autowired MockMvc mvc;
  @MockitoBean AuthService auth;
  @MockitoBean AccountService accounts;
  @MockitoBean PolicyService policies;

  @Test
  void publicReadinessDoesNotPretendLoginIsConfigured() throws Exception {
    when(auth.providers())
        .thenReturn(new Providers(List.of(new Provider("GOOGLE", "Google", false)), false));
    when(policies.current("ko-KR")).thenReturn(new CurrentPolicies(false, List.of()));
    mvc.perform(get("/api/v1/auth/providers"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.providers[0].enabled").value(false))
        .andExpect(jsonPath("$.registrationAvailable").value(false));
    mvc.perform(get("/api/v1/policy-versions/current"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(false));
  }

  @Test
  void legacyDisabledFlagDoesNotExposeUsersOrCouples() throws Exception {
    mvc.perform(get("/api/v1/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errorCode").value("SESSION_REQUIRED"));
    mvc.perform(post("/api/v1/couples/join")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/logout")).andExpect(status().isUnauthorized());
    verifyNoInteractions(accounts);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "null",
        "{",
        "{\"provider\":123,\"redirectUri\":\"betchu://auth/callback\"}",
        "{\"provider\":\"GOOGLE\",\"redirectUri\":\"betchu://auth/callback\",\"userId\":\"private\"}"
      })
  void malformedLoginInputsAreRejectedWithoutCoercion(String body) throws Exception {
    mvc.perform(
            post("/api/v1/auth/login/start").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    verifyNoInteractions(auth);
  }

  @Test
  void webAuthorizationHeaderPreflightWorks() throws Exception {
    mvc.perform(
            options("/api/v1/users/me")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"));
    mvc.perform(
            options("/api/v1/couples/invites/00000000-0000-0000-0000-000000000001")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "DELETE")
                .header("Access-Control-Request-Headers", "authorization"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,DELETE"));
  }

  @Test
  void loginSecretAloneCannotExchangeACompletedAttempt() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"loginId\":\"00000000-0000-0000-0000-000000000001\",\"loginSecret\":\"test-secret\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    verifyNoInteractions(auth);
  }
}
