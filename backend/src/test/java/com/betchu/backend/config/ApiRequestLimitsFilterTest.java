package com.betchu.backend.config;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ApiRequestLimitsFilterTest {
  private final ApiRequestLimitsFilter filter =
      new ApiRequestLimitsFilter(
          Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC), 3, 2);

  private MockHttpServletRequest request(String path) {
    var req = new MockHttpServletRequest("POST", path);
    req.setServletPath(path);
    req.setRemoteAddr("127.0.0.1");
    return req;
  }

  @Test
  void boundsKnownAndChunkedJsonWithoutInvokingTheController() throws Exception {
    for (boolean known : new boolean[] {true, false}) {
      var req = request("/api/v1/quests");
      req.setContentType("application/json");
      req.setContent(new byte[65537]);
      jakarta.servlet.http.HttpServletRequest input =
          known
              ? req
              : new jakarta.servlet.http.HttpServletRequestWrapper(req) {
                @Override
                public long getContentLengthLong() {
                  return -1;
                }
              };
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      filter.doFilter(input, response, chain);
      assertThat(response.getStatus()).isEqualTo(413);
      assertThat(chain.getRequest()).isNull();
    }
  }

  @Test
  void preservesUtf8BodyAndNeverTrustsForwardedHeadersForRateLimits() throws Exception {
    for (int n = 0; n < 3; n++) {
      var req = request("/api/v1/auth/login/start");
      req.addHeader("X-Forwarded-For", "192.0.2." + n);
      req.setContentType("application/json");
      req.setContent("{\"text\":\"배츄\"}".getBytes(StandardCharsets.UTF_8));
      var response = new MockHttpServletResponse();
      var chain = new MockFilterChain();
      filter.doFilter(req, response, chain);
      if (n < 2) {
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest().getReader().readLine()).contains("배츄");
      } else {
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(chain.getRequest()).isNull();
      }
      assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
  }

  @Test
  void healthProbesAndCorsPreflightAreNotRateLimited() throws Exception {
    for (int n = 0; n < 5; n++) {
      var req = request("/actuator/health/readiness");
      var chain = new MockFilterChain();
      filter.doFilter(req, new MockHttpServletResponse(), chain);
      assertThat(chain.getRequest()).isNotNull();
    }
    var req = request("/api/v1/quests");
    req.setMethod("OPTIONS");
    var chain = new MockFilterChain();
    filter.doFilter(req, new MockHttpServletResponse(), chain);
    assertThat(chain.getRequest()).isNotNull();
  }
}
