package com.betchu.backend.couples;

import com.betchu.backend.common.ApiException;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;

public final class CoupleModels {
  private CoupleModels() {}

  public record PublicProfile(UUID id, String nickname, String profileImage) {}

  public record Couple(UUID id, Instant connectedAt, PublicProfile partner) {}

  public record PendingInvite(
      UUID id,
      String status,
      String role,
      Instant expiresAt,
      PublicProfile partner,
      boolean myConfirmed,
      boolean partnerConfirmed) {}

  public record State(Couple couple, PendingInvite pendingInvite) {}

  public record CreatedInvite(UUID inviteId, String code, Instant expiresAt) {}

  public record InvitePreview(UUID inviteId, PublicProfile inviter, Instant expiresAt) {}

  public record CodeRequest(String code) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CodeRequest fromJson(JsonNode input) {
      if (input == null
          || !input.isObject()
          || input.size() != 1
          || !input.path("code").isString()) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COUPLE_REQUEST", "초대 코드를 입력해 주세요.");
      }
      return new CodeRequest(input.path("code").asString());
    }
  }

  public record EndResult(String status) {}

  public record EndJob(
      UUID id,
      String status,
      int targetResourceCount,
      int processedResourceCount,
      Instant createdAt,
      Instant completedAt) {}

  public record EndStatus(EndJob job) {}
}
