package com.betchu.backend.tutorial;

import static org.assertj.core.api.Assertions.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.couples.CoupleService;
import com.betchu.backend.couples.RelationshipCleanupService;
import com.betchu.backend.tutorial.TutorialModels.Settlement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(
    properties = {
      "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "betchu.tutorial.maintenance-delay-ms=3600000"
    })
class TutorialSettlementIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired TutorialSettlementService settlement;
  @Autowired CoupleService couples;
  @Autowired RelationshipCleanupService cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  UUID alice, bob, relationship, aliceMonster;
  String failureHook;
  String failureTable;

  @BeforeEach
  void createOwnAccounts() {
    alice = account("Alice");
    bob = account("Bob");
    aliceMonster =
        jdbc.queryForObject(
            "SELECT monster_id FROM user_active_monsters WHERE user_id=?", UUID.class, alice);
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    couples.confirm(alice, invite.inviteId());
    relationship = couples.confirm(bob, invite.inviteId()).couple().id();
  }

  @AfterEach
  void removeOnlyOwnFixtures() {
    removeFailure();
    Object[] users = {alice, bob};
    tx().executeWithoutResult(
            status -> {
              jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
              jdbc.update(
                  "DELETE FROM monster_growth_milestone_claims WHERE monster_id IN (SELECT id FROM monsters WHERE user_id IN (?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_partner_result_events WHERE quest_partner_result_id IN (SELECT r.id FROM quest_partner_results r JOIN quests q ON q.id=r.quest_id WHERE q.creator_id IN (?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_partner_results WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_cancel_requests WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_approvals WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_settlements WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?))",
                  users);
              jdbc.update("DELETE FROM tutorial_request_receipts WHERE user_id IN (?,?)", users);
              jdbc.update(
                  "DELETE FROM relationship_end_job_items WHERE resource_id IN (SELECT id FROM quests WHERE creator_id IN (?,?))",
                  users);
              jdbc.update("DELETE FROM wallet_ledger WHERE user_id IN (?,?)", users);
              jdbc.update(
                  "UPDATE quests SET current_quest_version_id=NULL,approved_quest_version_id=NULL WHERE creator_id IN (?,?)",
                  users);
              jdbc.update(
                  "DELETE FROM quest_versions WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?))",
                  users);
              jdbc.update("DELETE FROM quests WHERE creator_id IN (?,?)", users);
              jdbc.update("DELETE FROM wallets WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM user_active_monsters WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM monsters WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM user_progressions WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM couple_action_receipts WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM couple_code_attempts WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (?,?)", users);
              jdbc.update("DELETE FROM couple_pairing_slots WHERE user_id IN (?,?)", users);
              jdbc.update("DELETE FROM relationship_end_jobs WHERE couple_id=?", relationship);
              jdbc.update("DELETE FROM couple_members WHERE couple_id=?", relationship);
              jdbc.update("DELETE FROM couples WHERE id=?", relationship);
              jdbc.update("DELETE FROM couple_invites WHERE inviter_id IN (?,?)", users);
              jdbc.update("DELETE FROM users WHERE id IN (?,?)", users);
            });
  }

  @Test
  void approvalLocksOneHundredExactlyOnceAndInsufficientFundsDoNotLeakAmounts() {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    tx().executeWithoutResult(tx -> settlement.lockStake(quest));
    balance(alice, 900, 100);
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", quest)).isEqualTo(1);
    assertThat(count("SELECT available_balance_after FROM wallet_ledger WHERE quest_id=?", quest))
        .isEqualTo(900);
    assertThat(count("SELECT locked_balance_after FROM wallet_ledger WHERE quest_id=?", quest))
        .isEqualTo(100);
    UUID other = tutorial(bob, Instant.now().plusSeconds(3600));
    jdbc.update("UPDATE wallets SET available_coins=99 WHERE user_id=?", bob);
    assertThatThrownBy(() -> tx().executeWithoutResult(tx -> settlement.lockStake(other)))
        .isInstanceOf(ApiException.class)
        .satisfies(
            e -> {
              assertThat(((ApiException) e).errorCode()).isEqualTo("QUEST_REQUIRES_REVISION");
              assertThat(e.getMessage()).doesNotContain("99", "100");
            });
    balance(bob, 99, 0);
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", other)).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT stake_locked_at FROM quests WHERE id=?", Timestamp.class, other))
        .isNull();
  }

  @Test
  void successCreditsRealBonusAndHatchesOnceWithoutRecognizedSuccessOrStreak() {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    select(quest, "SUCCESS");
    jdbc.update("UPDATE user_progressions SET streak=7 WHERE user_id=?", alice);
    Settlement result = settle(quest, "SUCCESS", null);
    assertThat(settle(quest, "SUCCESS", null)).isEqualTo(result);
    balance(alice, 1100, 0);
    balance(bob, 1000, 0);
    assertThat(result.reward()).isEqualTo(100);
    assertThat(result.xp()).isEqualTo(10);
    assertThat(result.creditedMonsterId()).isEqualTo(aliceMonster);
    assertThat(result.recognizedSuccessBefore()).isNull();
    assertThat(result.recognizedSuccessAfter()).isNull();
    assertThat(result.growthStageBefore()).isEqualTo("EGG");
    assertThat(result.growthStageAfter()).isEqualTo("BABY");
    assertThat(count("SELECT recognized_success_count FROM monsters WHERE id=?", aliceMonster))
        .isZero();
    assertThat(count("SELECT current_level_exp FROM user_progressions WHERE user_id=?", alice))
        .isEqualTo(10);
    assertThat(count("SELECT streak FROM user_progressions WHERE user_id=?", alice)).isEqualTo(7);
    assertThat(
            count(
                "SELECT count(*) FROM monster_growth_milestone_claims WHERE monster_id=?",
                aliceMonster))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", quest))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", quest)).isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM quest_partner_result_events WHERE event_type='FINAL_APPROVE' AND quest_partner_result_id=(SELECT id FROM quest_partner_results WHERE quest_id=?)",
                quest))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT tutorial_completed_at FROM users WHERE id=?", Timestamp.class, alice))
        .isNotNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"FAILURE", "INVALID", "CANCELED"})
  void unsuccessfulApprovedTutorialRefundsOnlyItsPrincipal(String status) {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    if ("FAILURE".equals(status)) select(quest, "FAILURE");
    String reason = "INVALID".equals(status) ? "RESULT_CONFIRMATION_TIMEOUT" : null;
    Settlement result = settle(quest, status, reason);
    assertThat(settle(quest, status, reason)).isEqualTo(result);
    balance(alice, 1000, 0);
    assertThat(result.reward()).isZero();
    assertThat(result.xp()).isZero();
    assertThat(result.creditedMonsterId()).isNull();
    eggUnchanged();
    assertThat(
            jdbc.queryForObject(
                "SELECT tutorial_completed_at FROM users WHERE id=?", Timestamp.class, alice))
        .isNotNull();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"REJECTED", "APPROVAL_EXPIRED", "CANCELED", "CANCELED_RELATIONSHIP_ENDED"})
  void endingBeforeApprovalConsumesTutorialWithoutCoinLedgerOrSettlement(String status) {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    assertThat(settle(quest, status, null)).isNull();
    assertThat(settle(quest, status, null)).isNull();
    balance(alice, 1000, 0);
    eggUnchanged();
    assertThat(count("SELECT row_version FROM quests WHERE id=?", quest)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", quest)).isZero();
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", quest)).isZero();
    assertThat(count("SELECT count(*) FROM quest_partner_results WHERE quest_id=?", quest))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT tutorial_completed_at FROM users WHERE id=?", Timestamp.class, alice))
        .isNotNull();
  }

  @Test
  void settlementFailureRollsBackWalletXpEggMilestoneAndCompletionTogether() {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    select(quest, "SUCCESS");
    long version = count("SELECT row_version FROM quests WHERE id=?", quest);
    installFailure(
        "monster_growth_milestone_claims", "NEW.monster_id='" + aliceMonster + "'::uuid");
    assertThatThrownBy(() -> settle(quest, "SUCCESS", null))
        .isInstanceOf(DataAccessException.class);
    balance(alice, 900, 100);
    eggUnchanged();
    assertThat(count("SELECT row_version FROM quests WHERE id=?", quest)).isEqualTo(version);
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", quest)).isZero();
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", quest)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT tutorial_completed_at FROM users WHERE id=?", Timestamp.class, alice))
        .isNull();
    removeFailure();
    settle(quest, "SUCCESS", null);
    balance(alice, 1100, 0);
  }

  @Test
  void concurrentFinalSettlementHasOneLedgerOneClaimAndOneVersionIncrement() throws Exception {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    select(quest, "SUCCESS");
    long version = count("SELECT row_version FROM quests WHERE id=?", quest);
    List<Object> results =
        race(() -> settle(quest, "SUCCESS", null), () -> settle(quest, "SUCCESS", null));
    assertThat(results.get(0)).isEqualTo(results.get(1));
    balance(alice, 1100, 0);
    assertThat(count("SELECT row_version FROM quests WHERE id=?", quest)).isEqualTo(version + 1);
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", quest))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM monster_growth_milestone_claims WHERE monster_id=?",
                aliceMonster))
        .isEqualTo(1);
  }

  @Test
  void delayedRelationshipCleanupUsesFrozenEndTimeAndNeverChangesMissingTutorial() {
    Instant due = Instant.parse("2020-01-02T00:00:00Z");
    UUID quest = tutorial(alice, due);
    approve(quest);
    couples.end(bob, UUID.randomUUID(), false);
    jdbc.update(
        "UPDATE couples SET ended_at=? WHERE id=?",
        Timestamp.from(due.minusSeconds(1)),
        relationship);
    cleanup.processPending();
    cleanup.processPending();
    assertThat(jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, quest))
        .isEqualTo("CANCELED_RELATIONSHIP_ENDED");
    balance(alice, 1000, 0);
    eggUnchanged();
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("COMPLETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT tutorial_completed_at FROM users WHERE id=?", Timestamp.class, bob))
        .isNull();
  }

  @Test
  void relationshipEndAtDueTimeInvalidatesAndRefundsApprovedTutorial() {
    Instant due = Instant.parse("2020-01-02T00:00:00Z");
    UUID quest = tutorial(alice, due);
    approve(quest);
    select(quest, "SUCCESS");
    couples.end(alice, UUID.randomUUID(), true);
    jdbc.update("UPDATE couples SET ended_at=? WHERE id=?", Timestamp.from(due), relationship);
    cleanup.processPending();
    assertThat(jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, quest))
        .isEqualTo("INVALID");
    assertThat(
            jdbc.queryForObject(
                "SELECT invalid_reason FROM quests WHERE id=?", String.class, quest))
        .isEqualTo("RELATIONSHIP_ENDED_BEFORE_FINAL_APPROVAL");
    balance(alice, 1000, 0);
    eggUnchanged();
    assertThat(
            count(
                "SELECT count(*) FROM user_blocks WHERE blocker_id=? AND blocked_id=?", alice, bob))
        .isEqualTo(1);
  }

  @Test
  void cleanupFailureDoesNotRestoreRelationshipAndThirtyDayResponsePurgeDoesNotLoseRefund() {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    jdbc.update(
        "INSERT INTO tutorial_request_receipts(user_id,idempotency_key,quest_id,action,request_hash,response_json,created_at) VALUES (?,?,?,'CREATE',?,?::jsonb,clock_timestamp())",
        alice,
        UUID.randomUUID(),
        quest,
        "0".repeat(64),
        "{\"private\":\"response\"}");
    couples.end(alice, UUID.randomUUID(), false);
    UUID job = couples.endStatus(alice).job().id();
    installFailure("quest_settlements", "NEW.quest_id='" + quest + "'::uuid");
    assertThatThrownBy(() -> cleanup.processItem(job, quest))
        .isInstanceOf(DataAccessException.class);
    assertThat(couples.me(alice).couple()).isNull();
    balance(alice, 900, 100);
    jdbc.update(
        "UPDATE couples SET ended_at=clock_timestamp()-interval '31 days' WHERE id=?",
        relationship);
    cleanup.purgeExpiredDraftText();
    assertThat(
            jdbc.queryForObject(
                "SELECT response_json FROM tutorial_request_receipts WHERE quest_id=?",
                String.class,
                quest))
        .isNull();
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("PROCESSING");
    removeFailure();
    cleanup.processPending();
    balance(alice, 1000, 0);
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("COMPLETED");
  }

  @Test
  void finalSuccessAndRelationshipEndCannotBothSettle() throws Exception {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    select(quest, "SUCCESS");
    race(() -> settle(quest, "SUCCESS", null), () -> couples.end(bob, UUID.randomUUID(), false));
    cleanup.processPending();
    assertThat(couples.me(alice).couple()).isNull();
    String status =
        jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, quest);
    assertThat(status).isIn("SUCCESS", "CANCELED_RELATIONSHIP_ENDED");
    balance(alice, "SUCCESS".equals(status) ? 1100 : 1000, 0);
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", quest))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", quest)).isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM monster_growth_milestone_claims WHERE monster_id=?",
                aliceMonster))
        .isEqualTo("SUCCESS".equals(status) ? 1 : 0);
  }

  @Test
  void endingSnapshotsBothTutorialAuthorsWithoutDisclosingPartnerResourceCounts() {
    UUID first = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(first);
    UUID second = tutorial(bob, Instant.now().plusSeconds(3600));
    couples.end(alice, UUID.randomUUID(), false);
    assertThat(couples.endStatus(alice).job().targetResourceCount()).isEqualTo(1);
    assertThat(couples.endStatus(bob).job().targetResourceCount()).isEqualTo(1);
    cleanup.processPending();
    assertThat(couples.endStatus(alice).job().processedResourceCount()).isEqualTo(1);
    assertThat(couples.endStatus(bob).job().processedResourceCount()).isEqualTo(1);
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("COMPLETED");
    assertThat(
            count("SELECT count(*) FROM quest_settlements WHERE quest_id IN (?,?)", first, second))
        .isEqualTo(1);
    balance(alice, 1000, 0);
    balance(bob, 1000, 0);
  }

  @Test
  void progressionCarriesExperienceAndUsesDocumentedLevelStats() {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    select(quest, "SUCCESS");
    jdbc.update("UPDATE user_progressions SET current_level_exp=75 WHERE user_id=?", alice);
    settle(quest, "SUCCESS", null);
    assertThat(count("SELECT level FROM user_progressions WHERE user_id=?", alice)).isEqualTo(2);
    assertThat(count("SELECT current_level_exp FROM user_progressions WHERE user_id=?", alice))
        .isEqualTo(5);
    assertThat(count("SELECT base_hp FROM user_progressions WHERE user_id=?", alice))
        .isEqualTo(108);
    assertThat(count("SELECT base_attack FROM user_progressions WHERE user_id=?", alice))
        .isEqualTo(12);
  }

  @Test
  void moneyOperationsCannotRunOutsideLifecycleTransaction() {
    assertThatThrownBy(() -> settlement.lockStake(UUID.randomUUID()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThatThrownBy(() -> settlement.settle(UUID.randomUUID(), "CANCELED", null))
        .isInstanceOf(IllegalTransactionStateException.class);
  }

  @Test
  void maximumLevelKeepsZeroExperienceWhileHatchingAndPayingBonus() {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    select(quest, "SUCCESS");
    jdbc.update("UPDATE user_progressions SET level=50,current_level_exp=0 WHERE user_id=?", alice);
    Settlement result = settle(quest, "SUCCESS", null);
    assertThat(result.xp()).isZero();
    assertThat(result.reward()).isEqualTo(100);
    assertThat(count("SELECT current_level_exp FROM user_progressions WHERE user_id=?", alice))
        .isZero();
    assertThat(count("SELECT level FROM user_progressions WHERE user_id=?", alice)).isEqualTo(50);
    assertThat(
            count(
                "SELECT count(*) FROM monster_growth_milestone_claims WHERE monster_id=?",
                aliceMonster))
        .isEqualTo(1);
    balance(alice, 1100, 0);
  }

  @Test
  void terminalSettlementClosesOnlyPendingCancellationRequests() {
    UUID quest = tutorial(alice, Instant.now().plusSeconds(3600));
    approve(quest);
    jdbc.update(
        "INSERT INTO quest_cancel_requests(id,quest_id,requested_by,requested_at,status) VALUES (?,?,?,clock_timestamp(),'PENDING')",
        UUID.randomUUID(),
        quest,
        alice);
    settle(quest, "INVALID", "RESULT_CONFIRMATION_TIMEOUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM quest_cancel_requests WHERE quest_id=?", String.class, quest))
        .isEqualTo("EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT responded_at FROM quest_cancel_requests WHERE quest_id=?",
                Timestamp.class,
                quest))
        .isNotNull();
    balance(alice, 1000, 0);
  }

  private UUID account(String name) {
    UUID user = UUID.randomUUID(), monster = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,age_eligible_confirmed_at) VALUES (?,?,'ACTIVE',clock_timestamp())",
        user,
        name);
    jdbc.update(
        "INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,1000,0)", user);
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'SIGNUP_GRANT',1000,0)",
        UUID.randomUUID(),
        user);
    jdbc.update("INSERT INTO user_progressions(user_id) VALUES (?)", user);
    jdbc.update(
        "INSERT INTO monsters(id,user_id,name,species,acquisition_source) VALUES (?,?,'별이','STARLIGHT','STARTER')",
        monster,
        user);
    jdbc.update("INSERT INTO user_active_monsters(user_id,monster_id) VALUES (?,?)", user, monster);
    return user;
  }

  private UUID tutorial(UUID author, Instant due) {
    UUID quest = UUID.randomUUID(), version = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO quests(id,couple_id,creator_id,quest_type,source_type,status,created_at) VALUES (?,?,?,'TUTORIAL','SYSTEM','PENDING_APPROVAL',clock_timestamp())",
        quest,
        relationship,
        author);
    jdbc.update(
        """
        INSERT INTO quest_versions(id,quest_id,version_no,title,category,success_criteria,difficulty,stake,reward,xp,
          minimum_duration_minutes,evidence_method,due_at,result_at,approval_deadline_at,result_confirmation_deadline_at,submitted_by,submitted_at)
        VALUES (?,?,1,'물 한 잔','EATING','물 한 잔 마시기',1,100,100,10,0,'NONE',?,?,?,?,?,clock_timestamp())
        """,
        version,
        quest,
        Timestamp.from(due),
        Timestamp.from(due.plusSeconds(60)),
        Timestamp.from(due),
        Timestamp.from(due.plusSeconds(86460)),
        author);
    jdbc.update("UPDATE quests SET current_quest_version_id=? WHERE id=?", version, quest);
    return quest;
  }

  private void approve(UUID quest) {
    tx().executeWithoutResult(
            tx -> {
              settlement.lockStake(quest);
              jdbc.update(
                  "UPDATE quests SET status='ACTIVE',approved_at=clock_timestamp(),approved_quest_version_id=current_quest_version_id,row_version=row_version+1 WHERE id=?",
                  quest);
            });
  }

  private void select(UUID quest, String selected) {
    UUID creator =
        jdbc.queryForObject("SELECT creator_id FROM quests WHERE id=?", UUID.class, quest);
    UUID partner = creator.equals(alice) ? bob : alice;
    jdbc.update(
        "INSERT INTO quest_partner_results(id,quest_id,partner_id,selected_result,selection_revision,selected_at) VALUES (?,?,?,?,1,clock_timestamp())",
        UUID.randomUUID(),
        quest,
        partner,
        selected);
    jdbc.update(
        "UPDATE quests SET status='PENDING_FINAL_APPROVAL',row_version=row_version+1 WHERE id=?",
        quest);
  }

  private Settlement settle(UUID quest, String status, String reason) {
    return tx().execute(tx -> settlement.settle(quest, status, reason));
  }

  private TransactionTemplate tx() {
    return new TransactionTemplate(manager);
  }

  private long count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Long.class, args);
  }

  private void balance(UUID user, long available, long locked) {
    assertThat(count("SELECT available_coins FROM wallets WHERE user_id=?", user))
        .isEqualTo(available);
    assertThat(count("SELECT locked_coins FROM wallets WHERE user_id=?", user)).isEqualTo(locked);
  }

  private void eggUnchanged() {
    assertThat(
            jdbc.queryForObject(
                "SELECT growth_stage FROM monsters WHERE id=?", String.class, aliceMonster))
        .isEqualTo("EGG");
    assertThat(count("SELECT recognized_success_count FROM monsters WHERE id=?", aliceMonster))
        .isZero();
    assertThat(count("SELECT current_level_exp FROM user_progressions WHERE user_id=?", alice))
        .isZero();
  }

  private void installFailure(String table, String condition) {
    failureTable = table;
    failureHook = "test_tutorial_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION "
            + failureHook
            + "() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Test settlement failure''; END;'");
    jdbc.execute(
        "CREATE TRIGGER "
            + failureHook
            + " BEFORE INSERT ON "
            + table
            + " FOR EACH ROW WHEN ("
            + condition
            + ") EXECUTE FUNCTION "
            + failureHook
            + "()");
  }

  private void removeFailure() {
    if (failureHook == null) return;
    jdbc.execute("DROP TRIGGER IF EXISTS " + failureHook + " ON " + failureTable);
    jdbc.execute("DROP FUNCTION IF EXISTS " + failureHook + "()");
    failureHook = null;
    failureTable = null;
  }

  private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var left = pool.submit(() -> raced(first, ready, start));
      var right = pool.submit(() -> raced(second, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
    }
  }

  private static Object raced(Callable<?> action, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Concurrent test did not start");
    try {
      return action.call();
    } catch (RuntimeException failure) {
      return failure;
    }
  }
}
