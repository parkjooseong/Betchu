package com.betchu.backend.quests;

import static com.betchu.backend.quests.QuestLifecycleModels.*;
import static org.assertj.core.api.Assertions.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.couples.CoupleService;
import com.betchu.backend.couples.RelationshipCleanupService;
import com.betchu.backend.quests.QuestModels.*;
import com.betchu.backend.tutorial.TutorialModels.Result;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(
    properties = "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class PersonalQuestIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired QuestService drafts;
  @Autowired QuestLifecycleService quests;
  @Autowired QuestSettlementService settlement;
  @Autowired QuestBettingService betting;
  @Autowired CoupleService couples;
  @Autowired RelationshipCleanupService cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired JsonMapper mapper;
  UUID alice, bob, relationship, monster;
  String failureHook;

  @BeforeEach
  void fixtures() {
    alice = account("Alice");
    bob = account("Bob");
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    couples.confirm(alice, invite.inviteId());
    relationship = couples.confirm(bob, invite.inviteId()).couple().id();
    monster =
        jdbc.queryForObject(
            "SELECT monster_id FROM user_active_monsters WHERE user_id=?", UUID.class, alice);
  }

  @AfterEach
  void removeOnlyOwnedFixtures() {
    removeFailure();
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
              Object[] users = {alice, bob};
              String owned = " IN (SELECT id FROM quests WHERE creator_id IN (?,?))";
              jdbc.update(
                  "DELETE FROM monster_growth_milestone_claims WHERE monster_id IN (SELECT id FROM monsters WHERE user_id IN (?,?))",
                  users);
              jdbc.update("DELETE FROM monster_mastery_rewards WHERE user_id IN (?,?)", users);
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
                      "quest_drafts"))
                jdbc.update("DELETE FROM " + table + " WHERE quest_id" + owned, users);
              jdbc.update(
                  "DELETE FROM relationship_end_job_items WHERE resource_id" + owned, users);
              jdbc.update("DELETE FROM quest_request_receipts WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM wallet_ledger WHERE user_id IN (?,?)", users);
              jdbc.update(
                  "UPDATE quests SET current_quest_version_id=NULL,approved_quest_version_id=NULL WHERE creator_id IN (?,?)",
                  users);
              jdbc.update("DELETE FROM quest_versions WHERE quest_id" + owned, users);
              jdbc.update("DELETE FROM quests WHERE creator_id IN (?,?)", users);
              for (String table :
                  List.of(
                      "wallets",
                      "user_active_monsters",
                      "monsters",
                      "user_progressions",
                      "couple_action_receipts",
                      "couple_code_attempts",
                      "couple_pairing_slots",
                      "betting_rule_receipts",
                      "betting_rule_acknowledgments"))
                jdbc.update("DELETE FROM " + table + " WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (?,?)", users);
              jdbc.update("DELETE FROM relationship_end_jobs WHERE couple_id=?", relationship);
              jdbc.update("DELETE FROM couple_members WHERE couple_id=?", relationship);
              jdbc.update("DELETE FROM couples WHERE id=?", relationship);
              jdbc.update("DELETE FROM couple_invites WHERE inviter_id IN (?,?)", users);
              jdbc.update("DELETE FROM users WHERE id IN (?,?)", users);
            });
  }

  @Test
  void submissionGateAndPrivateRevisionHistory() {
    var d = draft(100);
    jdbc.update("UPDATE users SET tutorial_completed_at=NULL WHERE id=?", alice);
    assertThatThrownBy(() -> quests.submit(alice, d.id(), UUID.randomUUID(), new RowInput(0)))
        .isInstanceOf(ApiException.class);
    assertThat(n("SELECT count(*) FROM quest_versions WHERE quest_id=?", d.id())).isZero();
    jdbc.update("UPDATE users SET tutorial_completed_at=clock_timestamp() WHERE id=?", alice);
    var v = quests.submit(alice, d.id(), UUID.randomUUID(), new RowInput(0));
    balance(1000, 0);
    assertThatThrownBy(() -> quests.quote(bob, d.id())).isInstanceOf(ApiException.class);
    var changed =
        quests.requestChange(
            bob,
            d.id(),
            UUID.randomUUID(),
            new ChangeInput(v.questVersionId(), v.rowVersion(), " Make it clear "));
    assertThat(changed.decision().requestMessage()).isEqualTo("Make it clear");
    assertThatThrownBy(
            () ->
                quests.submit(alice, d.id(), UUID.randomUUID(), new RowInput(changed.rowVersion())))
        .isInstanceOf(ApiException.class);
    var edited =
        drafts.update(
            alice,
            d.id(),
            changed.rowVersion(),
            input("Private revision", 0, d.dueAt(), d.resultAt()));
    assertThat(quests.get(bob, d.id()).version().terms().title()).isEqualTo("A promise");
    assertThat(mapper.writeValueAsString(quests.get(bob, d.id())))
        .doesNotContain("Private revision", "\"draft\"");
    var next = quests.submit(alice, d.id(), UUID.randomUUID(), new RowInput(edited.rowVersion()));
    assertThat(next.version().versionNo()).isEqualTo(2);
    assertThat(next.version().changedFields()).containsExactlyInAnyOrder("title", "stake");
    assertThat(quests.versions(bob, d.id()).versions()).hasSize(2);
    assertThatThrownBy(
            () ->
                quests.approve(
                    bob,
                    d.id(),
                    UUID.randomUUID(),
                    new ApproveInput(v.questVersionId(), next.rowVersion(), Result.SUCCESS)))
        .isInstanceOf(ApiException.class);
    var recalled =
        quests.recall(
            alice,
            d.id(),
            UUID.randomUUID(),
            new VersionInput(next.questVersionId(), next.rowVersion()));
    assertThat(recalled.status()).isEqualTo("DRAFT");
    assertThat(quests.get(bob, d.id()).auditProjectionStatus()).isEqualTo("RECALLED");
    assertThat(quests.list(bob, "ALL", 20, null).quests()).isEmpty();
  }

  @Test
  void exactFinalApprovalPaysOnceAndNeverExposesUnconfirmedSelection() {
    var v = approved(100);
    balance(900, 100);
    assertThat(v.rewardReservedAmount()).isEqualTo(50);
    var open = open(v);
    var selected =
        quests.select(
            bob, v.id(), UUID.randomUUID(), new ResultInput(open.rowVersion(), Result.SUCCESS));
    balance(900, 100);
    assertThat(mapper.writeValueAsString(quests.get(alice, v.id())))
        .doesNotContain("partnerSelection", "selectedResult");
    assertThatThrownBy(
            () ->
                quests.finalApprove(
                    bob,
                    v.id(),
                    UUID.randomUUID(),
                    new FinalInput(selected.rowVersion(), Result.SUCCESS, 2)))
        .isInstanceOf(ApiException.class);
    UUID key = UUID.randomUUID();
    var body = new FinalInput(selected.rowVersion(), Result.SUCCESS, 1);
    var result = quests.finalApprove(bob, v.id(), key, body);
    assertThat(quests.finalApprove(bob, v.id(), key, body)).isEqualTo(result);
    balance(1050, 0);
    assertThat(result.settlement().xp()).isEqualTo(70);
    assertThat(result.settlement().recognizedSuccessAfter()).isEqualTo(1);
    assertThat(result.settlement().growthStageAfter()).isEqualTo("BABY");
    assertThat(n("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", v.id())).isEqualTo(2);
    assertThat(n("SELECT active_count FROM user_quest_counters WHERE user_id=?", alice)).isZero();
    assertThat(n("SELECT xp_slots_used FROM daily_activity_budgets WHERE user_id=?", alice))
        .isEqualTo(1);
    assertThat(n("SELECT reward_issued FROM daily_reward_budgets WHERE user_id=?", alice))
        .isEqualTo(50);
    assertThat(quests.summary(alice).current()).isEqualTo(1);
    assertThat(quests.summary(bob).current()).isEqualTo(1);
  }

  @Test
  void failureBurnsStakeResetsStreakAndConsumesDailySlots() {
    jdbc.update("UPDATE user_progressions SET streak=9 WHERE user_id=?", alice);
    var result = complete(approved(100), Result.FAILURE);
    balance(900, 0);
    assertThat(result.settlement().xp()).isZero();
    assertThat(n("SELECT streak FROM user_progressions WHERE user_id=?", alice)).isZero();
    assertThat(n("SELECT coin_slots_used FROM daily_reward_budgets WHERE user_id=?", alice))
        .isEqualTo(1);
    assertThat(n("SELECT reward_reserved FROM daily_reward_budgets WHERE user_id=?", alice))
        .isZero();
    assertThat(n("SELECT recognized_success_count FROM monsters WHERE id=?", monster)).isZero();
  }

  @Test
  void invalidRefundsAndReturnsAllSlotsButCancellationRetainsDailyUsage() {
    jdbc.update("UPDATE user_progressions SET streak=4 WHERE user_id=?", alice);
    var v = open(approved(100));
    var invalid = quests.rejectResult(bob, v.id(), UUID.randomUUID(), new RowInput(v.rowVersion()));
    assertThat(invalid.status()).isEqualTo("INVALID");
    balance(1000, 0);
    assertThat(n("SELECT coin_slots_used FROM daily_reward_budgets WHERE user_id=?", alice))
        .isZero();
    assertThat(n("SELECT xp_slots_used FROM daily_activity_budgets WHERE user_id=?", alice))
        .isZero();
    assertThat(n("SELECT streak FROM user_progressions WHERE user_id=?", alice)).isEqualTo(4);
    var another = approved(100);
    var cancel =
        quests.cancel(alice, another.id(), UUID.randomUUID(), new RowInput(another.rowVersion()));
    balance(900, 100);
    assertThatThrownBy(
            () ->
                quests.respondCancel(
                    alice, cancel.id(), UUID.randomUUID(), new RowInput(cancel.rowVersion()), true))
        .isInstanceOf(ApiException.class);
    assertThat(
            quests
                .respondCancel(
                    bob, cancel.id(), UUID.randomUUID(), new RowInput(cancel.rowVersion()), true)
                .status())
        .isEqualTo("CANCELED");
    balance(1000, 0);
    assertThat(n("SELECT coin_slots_used FROM daily_reward_budgets WHERE user_id=?", alice))
        .isEqualTo(1);
    assertThat(n("SELECT xp_slots_used FROM daily_activity_budgets WHERE user_id=?", alice))
        .isEqualTo(1);
  }

  @Test
  void zeroCoinStillUsesFiveActiveAndDailyXpSlots() {
    List<View> active = new ArrayList<>();
    for (int i = 0; i < 5; i++) active.add(approved(0));
    balance(1000, 0);
    var pending = submitted(0);
    assertThatThrownBy(() -> approve(pending)).isInstanceOf(ApiException.class);
    complete(active.getFirst(), Result.FAILURE);
    assertThatThrownBy(() -> approve(pending)).isInstanceOf(ApiException.class);
    var open = open(active.get(1));
    quests.rejectResult(bob, open.id(), UUID.randomUUID(), new RowInput(open.rowVersion()));
    assertThat(approve(pending).status()).isEqualTo("ACTIVE");
  }

  @Test
  void customCoinBudgetAndKstDateAreAuthoritative() {
    var first = approved(100);
    approved(100);
    var third = submitted(100);
    assertThat(quests.quote(alice, third.id()).allowedStakes()).containsExactly(0);
    assertThatThrownBy(() -> approve(third)).isInstanceOf(ApiException.class);
    assertThat(first.budgetDateKst())
        .isEqualTo(
            first
                .version()
                .terms()
                .resultAt()
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toLocalDate());
    var d =
        drafts.create(
            alice,
            UUID.randomUUID(),
            input(
                "Next date",
                100,
                Instant.parse("2035-01-01T14:00:00Z"),
                Instant.parse("2035-01-01T15:00:00Z")));
    var next = quests.submit(alice, d.id(), UUID.randomUUID(), new RowInput(0));
    assertThat(quests.quote(alice, next.id()).budgetDateKst()).isEqualTo(LocalDate.of(2035, 1, 2));
    assertThat(approve(next).rewardReservedAmount()).isEqualTo(50);
  }

  @Test
  void partialRewardIsReservedInTwentyFiveCoinChunks() {
    var v = submitted(100);
    quests.quote(alice, v.id());
    jdbc.update(
        "UPDATE daily_reward_budgets SET reward_issued=275,low_reward_issued=75 WHERE user_id=?",
        alice);
    assertThat(quests.quote(alice, v.id()).expectedReward()).isEqualTo(25);
    var active = approve(v);
    assertThat(active.rewardReservedAmount()).isEqualTo(25);
    assertThat(complete(active, Result.SUCCESS).settlement().reward()).isEqualTo(25);
    balance(1025, 0);
  }

  @Test
  void staleApprovalAfterDeadlineCommitsExpiryWithoutLockingCoins() {
    var v = submitted(100);
    shiftTimes(v.id(), -2);
    assertThatThrownBy(() -> approve(v)).isInstanceOf(ApiException.class);
    assertThat(quests.get(alice, v.id()).status()).isEqualTo("APPROVAL_EXPIRED");
    balance(1000, 0);
    assertThat(n("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", v.id())).isZero();
  }

  @Test
  void staleResultRequestCommitsTimeTransitionAndTimeoutRefundsOnce() {
    var v = approved(100);
    shiftTimes(v.id(), -2);
    assertThatThrownBy(
            () ->
                quests.select(
                    bob,
                    v.id(),
                    UUID.randomUUID(),
                    new ResultInput(v.rowVersion(), Result.SUCCESS)))
        .isInstanceOf(ApiException.class);
    assertThat(quests.get(alice, v.id()).status()).isEqualTo("AWAITING_RESULT");
    shiftTimes(v.id(), -86402);
    quests.processDue(v.id());
    quests.processDue(v.id());
    assertThat(quests.get(alice, v.id()).settlement().invalidReason())
        .isEqualTo("RESULT_CONFIRMATION_TIMEOUT");
    balance(1000, 0);
    assertThat(n("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", v.id())).isEqualTo(2);
  }

  @Test
  void repeatedInvalidationsSuspendOnlyCoinApprovalsAndRequireBothFreshAcknowledgments() {
    for (int i = 0; i < 3; i++) {
      var v = open(approved(100));
      quests.rejectResult(bob, v.id(), UUID.randomUUID(), new RowInput(v.rowVersion()));
    }
    assertThat(betting.get(alice).suspended()).isTrue();
    var coin = submitted(100);
    assertThatThrownBy(() -> approve(coin)).isInstanceOf(ApiException.class);
    assertThat(approved(0).status()).isEqualTo("ACTIVE");
    UUID key = UUID.randomUUID();
    var input = new AcknowledgeInput(1);
    var first = betting.acknowledge(alice, key, input);
    assertThat(first.suspended()).isTrue();
    assertThat(betting.acknowledge(alice, key, input)).isEqualTo(first);
    assertThat(betting.acknowledge(bob, UUID.randomUUID(), input).suspended()).isFalse();
    for (int i = 0; i < 3; i++) {
      var v = open(approved(100));
      quests.rejectResult(bob, v.id(), UUID.randomUUID(), new RowInput(v.rowVersion()));
    }
    assertThat(betting.get(alice).generation()).isEqualTo(2);
    betting.acknowledge(alice, key, input);
    assertThat(betting.get(alice).suspended()).isTrue();
    assertThat(betting.get(alice).acknowledgedAt()).isNull();
  }

  @ParameterizedTest
  @ValueSource(ints = {19, 39, 59, 79})
  void growthBackfillsMilestonesAndGrantsBoundMasteryOnce(int successes) {
    jdbc.update("UPDATE monsters SET recognized_success_count=? WHERE id=?", successes, monster);
    var v = complete(approved(0), Result.SUCCESS);
    assertThat(v.settlement().recognizedSuccessAfter()).isEqualTo(successes + 1);
    assertThat(v.settlement().growthStageAfter())
        .isEqualTo(successes == 19 ? "INTERMEDIATE" : "FINAL");
    assertThat(
            n("SELECT count(*) FROM monster_growth_milestone_claims WHERE monster_id=?", monster))
        .isEqualTo(successes == 19 ? 2 : successes == 39 ? 3 : successes == 59 ? 4 : 5);
    assertThat(
            n(
                "SELECT count(*) FROM monster_mastery_rewards WHERE user_id=? AND bound AND hp_bonus=0 AND attack_bonus=0",
                alice))
        .isEqualTo(successes >= 59 ? 3 : 0);
  }

  @Test
  void streakExperienceCarriesLevelsAndStopsAtFifty() {
    jdbc.update(
        "UPDATE user_progressions SET streak=2,current_level_exp=79 WHERE user_id=?", alice);
    var v = complete(approved(0), Result.SUCCESS);
    assertThat(v.settlement().xp()).isEqualTo(77);
    assertThat(n("SELECT level FROM user_progressions WHERE user_id=?", alice)).isEqualTo(2);
    assertThat(n("SELECT current_level_exp FROM user_progressions WHERE user_id=?", alice))
        .isEqualTo(76);
    jdbc.update("UPDATE user_progressions SET level=50,current_level_exp=0 WHERE user_id=?", alice);
    assertThat(complete(approved(0), Result.SUCCESS).settlement().xp()).isZero();
  }

  @Test
  void failedMilestoneInsertRollsBackAllSettlementWrites() {
    var v = open(approved(100));
    var selected =
        quests.select(
            bob, v.id(), UUID.randomUUID(), new ResultInput(v.rowVersion(), Result.SUCCESS));
    failureHook = "test_personal_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION "
            + failureHook
            + "() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Test settlement failure''; END;'");
    jdbc.execute(
        "CREATE TRIGGER "
            + failureHook
            + " BEFORE INSERT ON monster_growth_milestone_claims FOR EACH ROW WHEN (NEW.monster_id='"
            + monster
            + "'::uuid) EXECUTE FUNCTION "
            + failureHook
            + "()");
    var body = new FinalInput(selected.rowVersion(), Result.SUCCESS, 1);
    UUID key = UUID.randomUUID();
    assertThatThrownBy(() -> quests.finalApprove(bob, v.id(), key, body))
        .isInstanceOf(DataAccessException.class);
    balance(900, 100);
    assertThat(n("SELECT recognized_success_count FROM monsters WHERE id=?", monster)).isZero();
    assertThat(n("SELECT count(*) FROM quest_settlements WHERE quest_id=?", v.id())).isZero();
    removeFailure();
    assertThat(quests.finalApprove(bob, v.id(), key, body).status()).isEqualTo("SUCCESS");
    balance(1050, 0);
  }

  @Test
  void concurrentFinalRequestsHaveOneSettlement() throws Exception {
    var v = open(approved(100));
    var selected =
        quests.select(
            bob, v.id(), UUID.randomUUID(), new ResultInput(v.rowVersion(), Result.SUCCESS));
    var body = new FinalInput(selected.rowVersion(), Result.SUCCESS, 1);
    UUID key = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      Callable<View> task =
          () -> {
            start.await();
            return quests.finalApprove(bob, v.id(), key, body);
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
    }
    balance(1050, 0);
    assertThat(n("SELECT count(*) FROM quest_settlements WHERE quest_id=?", v.id())).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void relationshipEndUsesSavedEndTimeAndDeniesReplayBeforeCleanup(boolean pastDue) {
    var v = approved(100);
    if (pastDue) shiftTimes(v.id(), -2);
    couples.end(bob, UUID.randomUUID(), false);
    assertThatThrownBy(() -> quests.get(alice, v.id())).isInstanceOf(ApiException.class);
    cleanup.processPending();
    cleanup.processPending();
    assertThat(jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, v.id()))
        .isEqualTo(pastDue ? "INVALID" : "CANCELED_RELATIONSHIP_ENDED");
    balance(1000, 0);
    assertThat(n("SELECT xp_slots_used FROM daily_activity_budgets WHERE user_id=?", alice))
        .isEqualTo(pastDue ? 0 : 1);
  }

  @Test
  void retentionRedactsEvenSentinelNamedContentBeforeDelayedCleanupAndKeepsRefundTerms() {
    var d = draft(100);
    var edited =
        drafts.update(alice, d.id(), 0, input("보관 기간이 지난 퀘스트", 100, d.dueAt(), d.resultAt()));
    var submitted =
        quests.submit(alice, d.id(), UUID.randomUUID(), new RowInput(edited.rowVersion()));
    var active = approve(submitted);
    couples.end(bob, UUID.randomUUID(), false);
    jdbc.update(
        "UPDATE couples SET ended_at=clock_timestamp()-interval '31 days' WHERE id=?",
        relationship);
    cleanup.purgeExpiredDraftText();
    cleanup.purgeExpiredDraftText();
    assertThat(
            jdbc.queryForObject(
                "SELECT success_criteria FROM quest_versions WHERE quest_id=?",
                String.class,
                d.id()))
        .isEqualTo("보관 기간이 지났어요.");
    assertThat(
            n(
                "SELECT count(*) FROM quest_request_receipts WHERE quest_id=? AND response_json IS NOT NULL",
                d.id()))
        .isZero();
    assertThat(n("SELECT count(*) FROM quest_drafts WHERE quest_id=?", d.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                    "SELECT due_at FROM quest_versions WHERE quest_id=?", Timestamp.class, d.id())
                .toInstant())
        .isEqualTo(active.version().terms().dueAt());
    cleanup.processPending();
    balance(1000, 0);
  }

  private UUID account(String name) {
    UUID id = UUID.randomUUID(), m = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,age_eligible_confirmed_at,tutorial_completed_at) VALUES (?,?,'ACTIVE',clock_timestamp(),clock_timestamp())",
        id,
        name);
    jdbc.update("INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,1000,0)", id);
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'SIGNUP_GRANT',1000,0)",
        UUID.randomUUID(),
        id);
    jdbc.update("INSERT INTO user_progressions(user_id) VALUES (?)", id);
    jdbc.update(
        "INSERT INTO monsters(id,user_id,name,species,acquisition_source) VALUES (?,?,'별이','STARLIGHT','STARTER')",
        m,
        id);
    jdbc.update("INSERT INTO user_active_monsters(user_id,monster_id) VALUES (?,?)", id, m);
    return id;
  }

  private DraftInput input(String title, int stake, Instant due, Instant result) {
    return new DraftInput(
        title, Category.CUSTOM, "Read one chapter", 4, stake, due, result, 0, "NONE");
  }

  private DraftView draft(int stake) {
    Instant due = Instant.now().plusSeconds(3600);
    return drafts.create(
        alice, UUID.randomUUID(), input("A promise", stake, due, due.plusSeconds(60)));
  }

  private View submitted(int stake) {
    var d = draft(stake);
    return quests.submit(alice, d.id(), UUID.randomUUID(), new RowInput(0));
  }

  private View approve(View v) {
    return quests.approve(
        bob,
        v.id(),
        UUID.randomUUID(),
        new ApproveInput(v.questVersionId(), v.rowVersion(), Result.FAILURE));
  }

  private View approved(int stake) {
    return approve(submitted(stake));
  }

  // Move only this test's immutable fixture clock; production never rewrites submitted terms.
  private void shiftTimes(UUID id, int resultSeconds) {
    Instant result = Instant.now().plusSeconds(resultSeconds);
    jdbc.update(
        "UPDATE quest_versions SET due_at=?,result_at=?,approval_deadline_at=?,result_confirmation_deadline_at=? WHERE quest_id=?",
        Timestamp.from(result.minusSeconds(60)),
        Timestamp.from(result),
        Timestamp.from(result.minusSeconds(60)),
        Timestamp.from(result.plusSeconds(86400)),
        id);
  }

  private View open(View v) {
    shiftTimes(v.id(), -2);
    return quests.get(bob, v.id());
  }

  private View complete(View v, Result outcome) {
    var o = open(v);
    var s = quests.select(bob, v.id(), UUID.randomUUID(), new ResultInput(o.rowVersion(), outcome));
    return quests.finalApprove(
        bob,
        v.id(),
        UUID.randomUUID(),
        new FinalInput(s.rowVersion(), outcome, s.partnerSelection().selectionRevision()));
  }

  private long n(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private void balance(long available, long locked) {
    assertThat(n("SELECT available_coins FROM wallets WHERE user_id=?", alice))
        .isEqualTo(available);
    assertThat(n("SELECT locked_coins FROM wallets WHERE user_id=?", alice)).isEqualTo(locked);
  }

  private void removeFailure() {
    if (failureHook != null) {
      jdbc.execute("DROP TRIGGER IF EXISTS " + failureHook + " ON monster_growth_milestone_claims");
      jdbc.execute("DROP FUNCTION IF EXISTS " + failureHook + "()");
      failureHook = null;
    }
  }
}
