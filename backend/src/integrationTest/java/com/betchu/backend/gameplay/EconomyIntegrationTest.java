package com.betchu.backend.gameplay;

import static com.betchu.backend.gameplay.EconomyModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.couples.CoupleService;
import com.betchu.backend.game.MonsterService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(
    properties = "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class EconomyIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired EconomyService economy;
  @Autowired WeeklyBossService weeks;
  @Autowired MonsterService monsters;
  @Autowired CoupleService couples;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.betchu.backend.quests.QuestService drafts;
  @Autowired com.betchu.backend.quests.QuestLifecycleService quests;
  @MockitoBean DrawRandom random;
  @MockitoBean GameTime time;
  UUID alice, bob, couple;
  Instant now =
      Instant.now()
          .atZone(java.time.ZoneId.of("Asia/Seoul"))
          .toLocalDate()
          .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
          .plusDays(5)
          .atTime(12, 0)
          .atZone(java.time.ZoneId.of("Asia/Seoul"))
          .toInstant();

  @BeforeEach
  void setup() {
    when(time.now()).thenAnswer(inv -> now);
    when(random.nextInt(anyInt())).thenReturn(0);
    alice = account();
    bob = account();
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    couples.confirm(alice, invite.inviteId());
    couple = couples.confirm(bob, invite.inviteId()).couple().id();
  }

  @AfterEach
  void cleanup() {
    Object[] users = {alice, bob};
    String owned = " IN (SELECT id FROM quests WHERE creator_id IN (?,?))";
    jdbc.update(
        "DELETE FROM quest_partner_result_events WHERE quest_partner_result_id IN (SELECT id FROM quest_partner_results WHERE quest_id"
            + owned
            + ")",
        users);
    for (String table :
        List.of(
            "quest_partner_results",
            "quest_cancel_requests",
            "quest_approvals",
            "quest_settlements",
            "quest_recall_events",
            "couple_betting_incidents",
            "quest_drafts")) jdbc.update("DELETE FROM " + table + " WHERE quest_id" + owned, users);
    jdbc.update("DELETE FROM relationship_end_job_items WHERE resource_id" + owned, users);
    jdbc.update("DELETE FROM quest_request_receipts WHERE user_id IN (?,?)", users);
    jdbc.update("DELETE FROM wallet_ledger WHERE user_id IN (?,?)", users);
    jdbc.update(
        "UPDATE quests SET current_quest_version_id=NULL,approved_quest_version_id=NULL WHERE creator_id IN (?,?)",
        users);
    jdbc.update("DELETE FROM quest_versions WHERE quest_id" + owned, users);
    jdbc.update("DELETE FROM quests WHERE creator_id IN (?,?)", users);
    jdbc.update("DELETE FROM weekly_coop_bosses WHERE couple_id=?", couple);
    jdbc.update("DELETE FROM couple_action_receipts WHERE user_id IN (?,?)", users);
    jdbc.update("DELETE FROM relationship_end_jobs WHERE couple_id=?", couple);
    jdbc.update("DELETE FROM couple_members WHERE couple_id=?", couple);
    jdbc.update("DELETE FROM couples WHERE id=?", couple);
    for (String table :
        List.of(
            "wallet_ledger",
            "wallets",
            "user_active_monsters",
            "monsters",
            "user_progressions",
            "couple_action_receipts",
            "couple_code_attempts",
            "couple_pairing_slots"))
      jdbc.update("DELETE FROM " + table + " WHERE user_id IN (?,?)", users);
    jdbc.update("DELETE FROM couple_invites WHERE inviter_id IN (?,?)", users);
    jdbc.update("DELETE FROM users WHERE id IN (?,?)", users);
  }

  @Test
  void drawChargesOnceAndConvertsOnlyDuplicateItems() {
    UUID key = UUID.randomUUID();
    DrawInput input = new DrawInput("NORMAL", "NORMAL-v1", "COINS");
    var first = economy.draw(alice, key, input);
    assertThat(first.duplicate()).isFalse();
    assertThat(economy.draw(alice, key, input)).isEqualTo(first);
    var second = economy.draw(alice, UUID.randomUUID(), input);
    assertThat(second.duplicate()).isTrue();
    assertThat(second.fragmentsGranted()).isEqualTo(5);
    var state = economy.get(alice);
    assertThat(state.availableCoins()).isEqualTo(99800);
    assertThat(state.inventory()).hasSize(1);
    assertThat(state.inventory().getFirst().fragments()).isEqualTo(5);
    assertThat(economy.history(bob, null).entries()).isEmpty();
    assertThat(economy.history(alice, null).entries()).hasSize(2);
    assertThatThrownBy(
            () -> economy.draw(alice, key, new DrawInput("ADVANCED", "ADVANCED-v1", "COINS")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void pityIsIndependentGuaranteedAndResets() {
    for (int i = 0; i < 9; i++)
      assertThat(
              economy
                  .draw(alice, UUID.randomUUID(), new DrawInput("ADVANCED", "ADVANCED-v1", "COINS"))
                  .pityApplied())
          .isFalse();
    var advanced =
        economy.draw(alice, UUID.randomUUID(), new DrawInput("ADVANCED", "ADVANCED-v1", "COINS"));
    assertThat(advanced.item().rarity()).isEqualTo("LEGENDARY");
    assertThat(advanced.pityApplied()).isTrue();
    assertThat(advanced.pityAfter()).isZero();
    for (int i = 0; i < 19; i++)
      economy.draw(alice, UUID.randomUUID(), new DrawInput("LEGENDARY", "LEGENDARY-v1", "COINS"));
    var legendary =
        economy.draw(alice, UUID.randomUUID(), new DrawInput("LEGENDARY", "LEGENDARY-v1", "COINS"));
    assertThat(legendary.item().rarity()).isEqualTo("MYTHIC");
    assertThat(legendary.pityApplied()).isTrue();
    when(random.nextInt(10000)).thenReturn(9999);
    assertThat(
            economy
                .draw(alice, UUID.randomUUID(), new DrawInput("ADVANCED", "ADVANCED-v1", "COINS"))
                .pityAfter())
        .isZero();
  }

  @Test
  void ticketAndInvalidOddsCannotSpendCoins() {
    jdbc.update("INSERT INTO draw_tickets(user_id,normal_tickets) VALUES (?,1)", alice);
    var draw =
        economy.draw(alice, UUID.randomUUID(), new DrawInput("NORMAL", "NORMAL-v1", "TICKET"));
    assertThat(draw.cost()).isZero();
    assertThat(economy.get(alice).availableCoins()).isEqualTo(100000);
    assertThatThrownBy(
            () ->
                economy.draw(
                    alice, UUID.randomUUID(), new DrawInput("NORMAL", "NORMAL-v1", "TICKET")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () ->
                economy.draw(
                    alice, UUID.randomUUID(), new DrawInput("NORMAL", "NORMAL-old", "COINS")))
        .isInstanceOf(ApiException.class);
    assertThat(economy.get(alice).recentDraws()).hasSize(1);
  }

  @Test
  void concurrentSameKeyDrawOnlyCreatesOneCharge() throws Exception {
    UUID key = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Draw> action =
          () -> {
            start.await();
            return economy.draw(alice, key, new DrawInput("NORMAL", "NORMAL-v1", "COINS"));
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      start.countDown();
      assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(b.get(10, TimeUnit.SECONDS));
    }
    assertThat(economy.get(alice).availableCoins()).isEqualTo(99900);
    assertThat(economy.get(alice).recentDraws()).hasSize(1);
  }

  @Test
  void concurrentDifferentKeysCannotOverdraw() throws Exception {
    jdbc.update("UPDATE wallets SET available_coins=100 WHERE user_id=?", alice);
    try (var pool = Executors.newFixedThreadPool(2)) {
      Callable<Boolean> action =
          () -> {
            try {
              economy.draw(alice, UUID.randomUUID(), new DrawInput("NORMAL", "NORMAL-v1", "COINS"));
              return true;
            } catch (ApiException ex) {
              return false;
            }
          };
      var a = pool.submit(action);
      var b = pool.submit(action);
      assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
    assertThat(economy.get(alice).availableCoins()).isZero();
    assertThat(economy.get(alice).recentDraws()).hasSize(1);
  }

  @Test
  void failedRandomSelectionRollsBackTheEntirePurchase() {
    when(random.nextInt(10000)).thenThrow(new IllegalStateException("simulated random failure"));
    assertThatThrownBy(
            () ->
                economy.draw(
                    alice, UUID.randomUUID(), new DrawInput("NORMAL", "NORMAL-v1", "COINS")))
        .isInstanceOf(IllegalStateException.class);
    var state = economy.get(alice);
    assertThat(state.availableCoins()).isEqualTo(100000);
    assertThat(state.inventory()).isEmpty();
    assertThat(state.recentDraws()).isEmpty();
    assertThat(economy.history(alice, null).entries()).isEmpty();
  }

  @Test
  void equipmentUsesOwnedSlotAndUpdatesCombatPowerWithoutChangingWeeklyBaseline() {
    jdbc.update("INSERT INTO inventory_items(user_id,item_id) VALUES (?,'flame-sword')", alice);
    var before = weeks.get(alice).boss();
    economy.equip(alice, UUID.randomUUID(), new EquipInput("WEAPON", "flame-sword", 0));
    assertThat(monsters.me(alice).monster().combatPower()).isEqualTo(1100);
    assertThat(weeks.get(alice).boss().self().weeklyGrowth()).isEqualTo(400);
    assertThat(weeks.get(alice).boss().requiredPower()).isEqualTo(before.requiredPower());
    assertThatThrownBy(
            () -> economy.equip(alice, UUID.randomUUID(), new EquipInput("WEAPON", null, 0)))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> economy.equip(bob, UUID.randomUUID(), new EquipInput("WEAPON", "flame-sword", 0)))
        .isInstanceOf(ApiException.class);
    economy.equip(alice, UUID.randomUUID(), new EquipInput("WEAPON", null, 1));
    assertThat(monsters.me(alice).monster().combatPower()).isEqualTo(700);
  }

  @Test
  void rescueRequiresTimeAndBalanceAndOnlyPaysOnce() {
    assertThat(economy.rescue(alice).eligible()).isFalse();
    jdbc.update("UPDATE wallets SET available_coins=0,locked_coins=100 WHERE user_id=?", alice);
    assertThat(economy.rescue(alice).reason()).isEqualTo("LOCKED_COINS");
    jdbc.update("UPDATE wallets SET locked_coins=0 WHERE user_id=?", alice);
    var quest = economy.startRescue(alice, UUID.randomUUID()).quest();
    UUID key = UUID.randomUUID();
    var input = new CompleteRescue(quest.id(), true);
    assertThatThrownBy(() -> economy.completeRescue(alice, key, input))
        .isInstanceOf(ApiException.class);
    now = now.plusSeconds(60);
    var completed = economy.completeRescue(alice, key, input);
    assertThat(completed.quest().status()).isEqualTo("COMPLETED");
    assertThat(economy.completeRescue(alice, key, input)).isEqualTo(completed);
    assertThat(economy.get(alice).availableCoins()).isEqualTo(100);
    jdbc.update("UPDATE wallets SET available_coins=0 WHERE user_id=?", alice);
    assertThat(economy.rescue(alice).reason()).isEqualTo("COOLDOWN");
    assertThat(monsters.me(alice).monster().currentLevelExp()).isZero();
  }

  @Test
  void rescueMonthlyLimitAndExpiryAreEnforced() {
    jdbc.update("UPDATE wallets SET available_coins=0 WHERE user_id=?", alice);
    for (int days : List.of(8, 16))
      jdbc.update(
          "INSERT INTO rescue_quests(id,user_id,status,started_at,available_at,expires_at,completed_at) VALUES (?,?,'COMPLETED',?,?,?,?)",
          UUID.randomUUID(),
          alice,
          Timestamp.from(now.minusSeconds(days * 86400L + 60)),
          Timestamp.from(now.minusSeconds(days * 86400L)),
          Timestamp.from(now.minusSeconds((days - 1) * 86400L)),
          Timestamp.from(now.minusSeconds(days * 86400L)));
    assertThat(economy.rescue(alice).reason()).isEqualTo("COOLDOWN");
    now = now.plusSeconds(15 * 86400L);
    var quest = economy.startRescue(alice, UUID.randomUUID()).quest();
    now = now.plusSeconds(86400);
    assertThat(economy.rescue(alice).quest().status()).isEqualTo("EXPIRED");
    assertThatThrownBy(
            () ->
                economy.completeRescue(
                    alice, UUID.randomUUID(), new CompleteRescue(quest.id(), true)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void bossRequiresChemistryAndBothPlayersAndRewardsOnlyOnce() throws Exception {
    var boss = weeks.get(alice).boss();
    assertThat(boss.requiredPower()).isEqualTo(1700);
    assertThat(boss.status()).isEqualTo("LOCKED");
    assertThatThrownBy(() -> weeks.join(alice, UUID.randomUUID(), boss.id(), boss.rowVersion()))
        .isInstanceOf(ApiException.class);
    earnChemistry();
    var available = weeks.get(alice).boss();
    assertThat(available.chemistry()).isEqualTo(10);
    assertThat(available.status()).isEqualTo("AVAILABLE");
    var waiting = weeks.join(alice, UUID.randomUUID(), boss.id(), available.rowVersion()).boss();
    assertThat(waiting.status()).isEqualTo("WAITING_PARTNER");
    assertThat(economy.get(alice).normalTickets()).isZero();
    jdbc.update("INSERT INTO inventory_items(user_id,item_id) VALUES (?,'flame-sword')", alice);
    economy.equip(alice, UUID.randomUUID(), new EquipInput("WEAPON", "flame-sword", 0));
    UUID key = UUID.randomUUID();
    WeeklyBossService.Week done;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var gate = new CountDownLatch(1);
      Callable<WeeklyBossService.Week> join =
          () -> {
            gate.await();
            return weeks.join(bob, key, boss.id(), waiting.rowVersion());
          };
      var first = pool.submit(join);
      var second = pool.submit(join);
      gate.countDown();
      done = first.get(10, TimeUnit.SECONDS);
      assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(done);
    }
    assertThat(done.boss().status()).isEqualTo("CLEARED");
    assertThat(done.boss().partner().finalPower()).isEqualTo(1100);
    assertThat(weeks.join(bob, key, boss.id(), waiting.rowVersion())).isEqualTo(done);
    assertThat(economy.get(alice).normalTickets()).isEqualTo(1);
    assertThat(economy.get(bob).normalTickets()).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_events WHERE couple_id=? AND kind='BOSS'",
                Integer.class,
                couple))
        .isEqualTo(6);
    assertThat(economy.get(alice).availableCoins()).isEqualTo(100000);
    couples.end(alice, UUID.randomUUID(), false);
    assertThat(weeks.get(bob).boss()).isNull();
    assertThatThrownBy(() -> weeks.join(bob, key, boss.id(), waiting.rowVersion()))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void failedExpiredAndEndedBossesCannotIssueTickets() {
    var boss = weeks.get(alice).boss();
    earnChemistry();
    var waiting =
        weeks
            .join(alice, UUID.randomUUID(), boss.id(), weeks.get(alice).boss().rowVersion())
            .boss();
    assertThat(weeks.join(bob, UUID.randomUUID(), boss.id(), waiting.rowVersion()).boss().status())
        .isEqualTo("FAILED");
    assertThat(economy.get(bob).normalTickets()).isZero();
    now = now.plusSeconds(7 * 86400L);
    var next = weeks.get(alice).boss();
    assertThat(next.id()).isNotEqualTo(boss.id());
    couples.end(alice, UUID.randomUUID(), false);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM weekly_coop_bosses WHERE id=?", String.class, next.id()))
        .isEqualTo("CANCELED_RELATIONSHIP_ENDED");
  }

  private void earnChemistry() {
    for (int i = 0; i < 10; i++) {
      UUID creator = i % 2 == 0 ? alice : bob, partner = i % 2 == 0 ? bob : alice;
      Instant due = Instant.now().plusSeconds(3600);
      var d =
          drafts.create(
              creator,
              UUID.randomUUID(),
              new com.betchu.backend.quests.QuestModels.DraftInput(
                  "Fixture",
                  com.betchu.backend.quests.QuestModels.Category.CUSTOM,
                  "Read one page",
                  1,
                  0,
                  due,
                  due.plusSeconds(60),
                  0,
                  "NONE"));
      var s =
          quests.submit(
              creator,
              d.id(),
              UUID.randomUUID(),
              new com.betchu.backend.quests.QuestLifecycleModels.RowInput(0));
      quests.approve(
          partner,
          d.id(),
          UUID.randomUUID(),
          new com.betchu.backend.quests.QuestLifecycleModels.ApproveInput(
              s.questVersionId(),
              s.rowVersion(),
              com.betchu.backend.tutorial.TutorialModels.Result.FAILURE));
      Instant result = Instant.now().minusSeconds(2);
      // Advance only this test fixture's immutable terms to exercise the real settlement path.
      jdbc.update(
          "UPDATE quest_versions SET due_at=?,result_at=?,approval_deadline_at=?,result_confirmation_deadline_at=? WHERE quest_id=?",
          Timestamp.from(result.minusSeconds(60)),
          Timestamp.from(result),
          Timestamp.from(result.minusSeconds(60)),
          Timestamp.from(result.plusSeconds(86400)),
          d.id());
      var open = quests.get(partner, d.id());
      var selected =
          quests.select(
              partner,
              d.id(),
              UUID.randomUUID(),
              new com.betchu.backend.quests.QuestLifecycleModels.ResultInput(
                  open.rowVersion(), com.betchu.backend.tutorial.TutorialModels.Result.FAILURE));
      quests.finalApprove(
          partner,
          d.id(),
          UUID.randomUUID(),
          new com.betchu.backend.quests.QuestLifecycleModels.FinalInput(
              selected.rowVersion(),
              com.betchu.backend.tutorial.TutorialModels.Result.FAILURE,
              selected.partnerSelection().selectionRevision()));
    }
  }

  private UUID account() {
    UUID user = UUID.randomUUID(), monster = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,age_eligible_confirmed_at,tutorial_completed_at) VALUES (?,'Fixture','ACTIVE',now(),now())",
        user);
    jdbc.update(
        "INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,100000,0)", user);
    jdbc.update("INSERT INTO user_progressions(user_id) VALUES (?)", user);
    jdbc.update(
        "INSERT INTO monsters(id,user_id,name,species,acquisition_source) VALUES (?,?,'Fixture','STARLIGHT','STARTER')",
        monster,
        user);
    jdbc.update("INSERT INTO user_active_monsters(user_id,monster_id) VALUES (?,?)", user, monster);
    return user;
  }
}
