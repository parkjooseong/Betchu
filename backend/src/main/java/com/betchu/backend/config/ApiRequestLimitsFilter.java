package com.betchu.backend.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** Per-instance backstop; the trusted ingress must also enforce distributed request limits. */
public final class ApiRequestLimitsFilter extends OncePerRequestFilter {
  static final int MAX_JSON_BYTES = 65536;

  private record Window(long minute, int count) {}

  private final Map<String, Window> windows = new HashMap<>();
  private final Clock clock;
  private final int generalLimit, authLimit;
  private long lastMinute = -1;

  public ApiRequestLimitsFilter(Clock clock, int generalLimit, int authLimit) {
    this.clock = clock;
    this.generalLimit = generalLimit;
    this.authLimit = authLimit;
    if (generalLimit < 1 || authLimit < 1)
      throw new IllegalArgumentException("Request limits must be positive");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!request.getServletPath().startsWith("/api/v1/") || "OPTIONS".equals(request.getMethod())) {
      chain.doFilter(request, response);
      return;
    }
    response.setHeader("X-Request-Id", UUID.randomUUID().toString());
    response.setHeader("Cache-Control", "no-store");
    boolean auth = request.getServletPath().startsWith("/api/v1/auth/");
    // Forwarded headers are intentionally ignored. Only the configured ingress may normalize client
    // addresses.
    if (!admit(
        request.getRemoteAddr() + (auth ? ":auth" : ":api"), auth ? authLimit : generalLimit)) {
      response.setHeader(
          "Retry-After", Long.toString(60 - Math.floorMod(clock.instant().getEpochSecond(), 60)));
      problem(response, 429, "RATE_LIMITED", "요청이 많아요. 잠시 후 다시 시도해 주세요.");
      return;
    }
    String contentType = Objects.toString(request.getContentType(), "").toLowerCase(Locale.ROOT);
    String mediaType = contentType.split(";", 2)[0].trim();
    if (mediaType.equals("application/json") || mediaType.endsWith("+json")) {
      if (request.getContentLengthLong() > MAX_JSON_BYTES) {
        problem(response, 413, "REQUEST_TOO_LARGE", "요청 크기를 줄여 주세요.");
        return;
      }
      byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
      if (body.length > MAX_JSON_BYTES) {
        problem(response, 413, "REQUEST_TOO_LARGE", "요청 크기를 줄여 주세요.");
        return;
      }
      chain.doFilter(new JsonRequest(request, body), response);
    } else chain.doFilter(request, response);
  }

  private synchronized boolean admit(String key, int limit) {
    long minute = Math.floorDiv(clock.instant().getEpochSecond(), 60);
    if (minute != lastMinute) {
      windows.entrySet().removeIf(entry -> entry.getValue().minute() != minute);
      lastMinute = minute;
    }
    Window old = windows.get(key);
    if (old == null && windows.size() >= 20000) return false;
    int count = old == null ? 0 : old.count();
    if (count >= limit) return false;
    windows.put(key, new Window(minute, count + 1));
    return true;
  }

  private void problem(HttpServletResponse response, int status, String code, String detail)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            "{\"type\":\"about:blank\",\"title\":\"Request rejected\",\"status\":"
                + status
                + ",\"errorCode\":\""
                + code
                + "\",\"detail\":\""
                + detail
                + "\"}");
  }

  private static final class JsonRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    JsonRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      var input = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return input.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) {
          return input.read(target, offset, length);
        }

        @Override
        public boolean isFinished() {
          return input.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new IllegalStateException("Synchronous JSON request");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
