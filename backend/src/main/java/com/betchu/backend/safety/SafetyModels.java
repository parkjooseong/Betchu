package com.betchu.backend.safety;

import static com.betchu.backend.common.RequestFields.*;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;

public final class SafetyModels {
  private SafetyModels() {}

  public record ReportInput(UUID reportedUserId, String reason, String description) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReportInput parse(JsonNode input) {
      object(input, "reportedUserId", "reason", "description");
      return new ReportInput(
          uuid(text(input, "reportedUserId", 36)),
          choice(input, "reason", "HARASSMENT", "UNSAFE_REQUEST", "PRIVACY", "OTHER"),
          text(input, "description", 2000));
    }
  }

  public record ReportReceipt(UUID id, String status, Instant createdAt) {}

  public record DeleteInput(String confirmation) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DeleteInput parse(JsonNode input) {
      object(input, "confirmation");
      return new DeleteInput(choice(input, "confirmation", "DELETE_MY_ACCOUNT"));
    }
  }

  public record Deletion(String status, Instant requestedAt) {}

  public record ExportPage(
      int formatVersion,
      String section,
      Instant generatedAt,
      List<Map<String, Object>> records,
      String nextCursor) {}
}
