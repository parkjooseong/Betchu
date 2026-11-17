package com.betchu.backend.gameplay;

import static com.betchu.backend.common.RequestFields.*;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class EconomyModels {
  private EconomyModels() {}

  public record Item(
      String id,
      String name,
      String rarity,
      String slot,
      int hpBonus,
      int attackBonus,
      int combatPower,
      int duplicateFragments) {}

  public record OwnedItem(Item item, long fragments) {}

  public record Slot(String slot, String itemId, long rowVersion) {}

  public record Odds(
      String versionId,
      String type,
      int cost,
      List<Integer> weights,
      int pityThreshold,
      String pityRarity,
      int misses,
      List<String> itemIds) {}

  public record Draw(
      UUID id,
      String probabilityVersionId,
      String payment,
      int cost,
      Item item,
      boolean pityApplied,
      int pityBefore,
      int pityAfter,
      boolean duplicate,
      int fragmentsGranted,
      Instant createdAt) {}

  public record Economy(
      long availableCoins,
      long lockedCoins,
      int normalTickets,
      List<Item> catalog,
      List<Odds> draws,
      List<OwnedItem> inventory,
      List<Slot> equipment,
      List<Draw> recentDraws) {}

  public record DrawInput(String type, String probabilityVersionId, String payment) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DrawInput parse(JsonNode input) {
      object(input, "type", "probabilityVersionId", "payment");
      return new DrawInput(
          choice(input, "type", "NORMAL", "ADVANCED", "LEGENDARY"),
          text(input, "probabilityVersionId", 30),
          choice(input, "payment", "COINS", "TICKET"));
    }
  }

  public record EquipInput(String slot, String itemId, long rowVersion) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static EquipInput parse(JsonNode input) {
      object(input, "slot", "itemId", "rowVersion");
      return new EquipInput(
          choice(input, "slot", "WEAPON", "ARMOR", "ACCESSORY"),
          input.path("itemId").isNull() ? null : text(input, "itemId", 60),
          revision(input));
    }
  }

  public record Rescue(
      UUID id,
      String status,
      String title,
      Instant startedAt,
      Instant availableAt,
      Instant expiresAt,
      Instant completedAt) {}

  public record RescueStatus(
      Instant serverTime, boolean eligible, String reason, Instant nextEligibleAt, Rescue quest) {}

  public record CompleteRescue(UUID questId, boolean completed) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static CompleteRescue parse(JsonNode input) {
      object(input, "questId", "completed");
      if (!input.path("completed").isBoolean() || !input.path("completed").asBoolean())
        throw invalid();
      return new CompleteRescue(uuid(text(input, "questId", 36)), true);
    }
  }

  public record LedgerEntry(
      UUID id, String type, long availableDelta, long lockedDelta, Instant createdAt) {}

  public record WalletHistory(List<LedgerEntry> entries, UUID nextCursor) {}

  public record JoinInput(UUID bossId, long rowVersion) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static JoinInput parse(JsonNode input) {
      object(input, "bossId", "rowVersion");
      return new JoinInput(uuid(text(input, "bossId", 36)), revision(input));
    }
  }
}
