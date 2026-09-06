package com.betchu.backend.tutorial;

import static com.betchu.backend.tutorial.TutorialModels.*;
import static org.assertj.core.api.Assertions.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.couples.CoupleService;
import com.betchu.backend.couples.RelationshipCleanupService;
import com.betchu.backend.game.MonsterService;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import com.betchu.backend.starter.StarterModels.Species;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(
    properties = "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class TutorialLifecycleIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired TutorialService tutorials;
  @Autowired CoupleService couples;
  @Autowired MonsterService monsters;
  @Autowired RelationshipCleanupService cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  @Autowired JsonMapper mapper;
  UUID alice, bob, carol, couple;

  @BeforeEach
  void setup() {
    alice = account("Alice");
    bob = account("Bob");
    carol = account("Carol");
    couple = connect(alice, bob);
    monsters.create(alice, UUID.randomUUID(), new PreviewRequest(Species.STARLIGHT, "별이"));
    monsters.create(bob, UUID.randomUUID(), new PreviewRequest(Species.WAVE, "파도"));
  }

  @AfterEach
  void removeOnlyOwnFixtures() {
    Object[] users = {alice, bob, carol};
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
              jdbc.update(
                  "DELETE FROM relationship_end_job_items WHERE resource_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM monster_growth_milestone_claims WHERE monster_id IN (SELECT id FROM monsters WHERE user_id IN (?,?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_settlements WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update("DELETE FROM wallet_ledger WHERE user_id IN (?,?,?)", users);
              jdbc.update(
                  "DELETE FROM quest_partner_result_events WHERE quest_partner_result_id IN (SELECT r.id FROM quest_partner_results r JOIN quests q ON q.id=r.quest_id WHERE q.creator_id IN (?,?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_partner_results WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_approvals WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_cancel_requests WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update("DELETE FROM tutorial_request_receipts WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM quest_request_receipts WHERE user_id IN (?,?,?)", users);
              jdbc.update(
                  "UPDATE quests SET current_quest_version_id=NULL,approved_quest_version_id=NULL WHERE creator_id IN (?,?,?)",
                  users);
              jdbc.update(
                  "DELETE FROM quest_versions WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update(
                  "DELETE FROM quest_drafts WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update("DELETE FROM quests WHERE creator_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM starter_creation_receipts WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM user_active_monsters WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM monsters WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM user_progressions WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM wallets WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM couple_action_receipts WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM couple_code_attempts WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM couple_pairing_slots WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM relationship_end_jobs WHERE initiated_by IN (?,?,?)", users);
              List<UUID> relations =
                  jdbc.queryForList(
                      "SELECT DISTINCT couple_id FROM couple_members WHERE user_id IN (?,?,?)",
                      UUID.class,
                      users);
              jdbc.update("DELETE FROM couple_members WHERE user_id IN (?,?,?)", users);
              for (UUID id : relations) jdbc.update("DELETE FROM couples WHERE id=?", id);
              jdbc.update("DELETE FROM couple_invites WHERE inviter_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM users WHERE id IN (?,?,?)", users);
            });
  }

  @Test
  void successRequiresPartnerSelectionAndSeparateApprovalAndRewardsOnlyOnce() {
    assertThat(tutorials.overview(alice).canStart()).isTrue();
    View pending = create(alice);
    assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
    wallet(alice, 1000, 0);
    assertThat(pending.terms().title()).isEqualTo(TEMPLATE.title());
    assertThat(pending.terms().stake()).isEqualTo(100);
    View active = approve(pending);
    wallet(alice, 900, 100);
    assertThat(active.predictedResult()).isEqualTo(Result.FAILURE);
    error(
        () ->
            tutorials.selectResult(
                alice,
                active.id(),
                UUID.randomUUID(),
                new ResultInput(active.rowVersion(), Result.SUCCESS)),
        "TUTORIAL_NOT_FOUND");
    View awaiting = openResults(active);
    UUID selectKey = UUID.randomUUID();
    ResultInput input = new ResultInput(awaiting.rowVersion(), Result.SUCCESS);
    View selected = tutorials.selectResult(bob, active.id(), selectKey, input);
    assertThat(selected.status()).isEqualTo("PENDING_FINAL_APPROVAL");
    assertThat(tutorials.selectResult(bob, active.id(), selectKey, input)).isEqualTo(selected);
    View creatorView = tutorials.get(alice, active.id());
    assertThat(creatorView.partnerSelection()).isNull();
    assertThat(mapper.valueToTree(creatorView).has("partnerSelection")).isFalse();
    assertThat(creatorView.allowedActions()).isEmpty();
    wallet(alice, 900, 100);
    assertThat(monsters.me(alice).monster().growthStage().name()).isEqualTo("EGG");
    UUID finalKey = UUID.randomUUID();
    ResultInput finalInput = new ResultInput(selected.rowVersion(), Result.SUCCESS);
    View resolved = tutorials.finalApprove(bob, active.id(), finalKey, finalInput);
    assertThat(tutorials.finalApprove(bob, active.id(), finalKey, finalInput)).isEqualTo(resolved);
    assertThat(resolved.status()).isEqualTo("SUCCESS");
    wallet(alice, 1100, 0);
    assertThat(resolved.settlement().reward()).isEqualTo(100);
    assertThat(resolved.settlement().xp()).isEqualTo(10);
    assertThat(resolved.settlement().recognizedSuccessBefore()).isNull();
    assertThat(resolved.settlement().recognizedSuccessAfter()).isNull();
    var monster = monsters.me(alice).monster();
    assertThat(monster.growthStage().name()).isEqualTo("BABY");
    assertThat(monster.currentLevelExp()).isEqualTo(10);
    assertThat(monster.streak()).isZero();
    assertThat(monster.recognizedSuccessCount()).isZero();
    assertThat(monster.reachedMilestones()).hasSize(1);
    assertThat(tutorials.overview(alice).completedAt()).isNotNull();
    assertThat(tutorials.overview(alice).canStart()).isFalse();
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", active.id()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", active.id()))
        .isEqualTo(2);
    error(() -> create(alice), "TUTORIAL_ALREADY_EXISTS");
  }

  @Test
  void failedTutorialRefundsInsteadOfBurningAndNeverResetsStreak() {
    jdbc.update("UPDATE user_progressions SET streak=4 WHERE user_id=?", alice);
    View awaiting = openResults(approve(create(alice)));
    View selected =
        tutorials.selectResult(
            bob,
            awaiting.id(),
            UUID.randomUUID(),
            new ResultInput(awaiting.rowVersion(), Result.FAILURE));
    View result =
        tutorials.finalApprove(
            bob,
            selected.id(),
            UUID.randomUUID(),
            new ResultInput(selected.rowVersion(), Result.FAILURE));
    assertThat(result.status()).isEqualTo("FAILURE");
    wallet(alice, 1000, 0);
    assertThat(result.settlement().reward()).isZero();
    assertThat(result.settlement().xp()).isZero();
    assertThat(monsters.me(alice).monster().streak()).isEqualTo(4);
    assertThat(monsters.me(alice).monster().growthStage().name()).isEqualTo("EGG");
    error(() -> create(alice), "TUTORIAL_ALREADY_EXISTS");
  }

  @Test
  void wrongRoleCurrentVersionAndInsufficientFundsLeaveApprovalUnchanged() {
    View pending = create(alice);
    error(
        () ->
            tutorials.approve(
                alice,
                pending.id(),
                UUID.randomUUID(),
                new ApproveInput(pending.questVersionId(), 0, Result.SUCCESS)),
        "TUTORIAL_NOT_FOUND");
    error(() -> tutorials.get(carol, pending.id()), "TUTORIAL_NOT_FOUND");
    error(
        () ->
            tutorials.approve(
                bob,
                pending.id(),
                UUID.randomUUID(),
                new ApproveInput(UUID.randomUUID(), 0, Result.SUCCESS)),
        "QUEST_VERSION_CONFLICT");
    jdbc.update("UPDATE wallets SET available_coins=50 WHERE user_id=?", alice);
    error(() -> approve(pending), "QUEST_REQUIRES_REVISION");
    assertThat(tutorials.get(bob, pending.id()).status()).isEqualTo("PENDING_APPROVAL");
    assertThat(tutorials.get(bob, pending.id()).rowVersion()).isZero();
    wallet(alice, 50, 0);
    assertThat(count("SELECT count(*) FROM quest_approvals WHERE quest_id=?", pending.id()))
        .isZero();
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", pending.id())).isZero();
  }

  @Test
  void creationNormalizesDatePrecisionAndNeverExtendsAnExistingTutorial() {
    Instant now = now();
    CreateInput input =
        new CreateInput(now.plusSeconds(3600).plusNanos(999), now.plusSeconds(7200).plusNanos(999));
    UUID key = UUID.randomUUID();
    View first = tutorials.create(alice, key, input);
    CreateInput normalized = new CreateInput(first.terms().dueAt(), first.terms().resultAt());
    assertThat(tutorials.create(alice, key, normalized)).isEqualTo(first);
    error(
        () ->
            tutorials.create(
                alice, key, new CreateInput(now.plusSeconds(3700), now.plusSeconds(7300))),
        "IDEMPOTENCY_KEY_REUSED");
    for (CreateInput invalid :
        List.of(
            new CreateInput(now.minusSeconds(1), now.plusSeconds(10)),
            new CreateInput(now.plusSeconds(20), now.plusSeconds(10)),
            new CreateInput(now.plusSeconds(3600), now.plusSeconds(8 * 86400))))
      error(() -> tutorials.create(bob, UUID.randomUUID(), invalid), "INVALID_TUTORIAL_REQUEST");
    assertThat(
            count("SELECT count(*) FROM quests WHERE creator_id=? AND quest_type='TUTORIAL'", bob))
        .isZero();
  }

  @Test
  void rejectionAndApprovalExpiryCompleteWithoutLockingCoins() {
    View pending = create(alice);
    View rejected =
        tutorials.reject(
            bob, pending.id(), UUID.randomUUID(), new RejectInput(pending.questVersionId(), 0));
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.settlement()).isNull();
    wallet(alice, 1000, 0);
    assertThat(tutorials.overview(alice).completedAt()).isNotNull();
    View other = create(bob);
    shiftTimes(other.id(), now().minusSeconds(1), now().plusSeconds(300));
    error(
        () ->
            tutorials.approve(
                alice,
                other.id(),
                UUID.randomUUID(),
                new ApproveInput(other.questVersionId(), 999, Result.SUCCESS)),
        "APPROVAL_EXPIRED");
    assertThat(tutorials.get(bob, other.id()).status()).isEqualTo("APPROVAL_EXPIRED");
    assertThat(tutorials.get(bob, other.id()).rowVersion()).isEqualTo(1);
    assertThat(tutorials.overview(bob).completedAt()).isNotNull();
    wallet(bob, 1000, 0);
    assertThat(
            count(
                "SELECT count(*) FROM quest_settlements WHERE quest_id IN (?,?)",
                pending.id(),
                other.id()))
        .isZero();
  }

  @Test
  void selectionCanChangeButAnOldFinalApprovalCannotSettleIt() {
    View awaiting = openResults(approve(create(alice)));
    View first =
        tutorials.selectResult(
            bob,
            awaiting.id(),
            UUID.randomUUID(),
            new ResultInput(awaiting.rowVersion(), Result.SUCCESS));
    View changed =
        tutorials.selectResult(
            bob,
            first.id(),
            UUID.randomUUID(),
            new ResultInput(first.rowVersion(), Result.FAILURE));
    assertThat(changed.partnerSelection().selectionRevision()).isEqualTo(2);
    error(
        () ->
            tutorials.finalApprove(
                bob,
                first.id(),
                UUID.randomUUID(),
                new ResultInput(first.rowVersion(), Result.SUCCESS)),
        "QUEST_VERSION_CONFLICT");
    error(
        () ->
            tutorials.finalApprove(
                bob,
                first.id(),
                UUID.randomUUID(),
                new ResultInput(changed.rowVersion(), Result.SUCCESS)),
        "RESULT_SELECTION_CHANGED");
    wallet(alice, 900, 100);
    View rejected =
        tutorials.rejectResult(
            bob, changed.id(), UUID.randomUUID(), new VersionInput(changed.rowVersion()));
    assertThat(rejected.settlement().invalidReason()).isEqualTo("PARTNER_FINAL_APPROVAL_REJECTED");
    wallet(alice, 1000, 0);
    assertThat(
            count(
                "SELECT count(*) FROM quest_partner_result_events e JOIN quest_partner_results r ON r.id=e.quest_partner_result_id WHERE r.quest_id=?",
                first.id()))
        .isEqualTo(3);
  }

  @Test
  void deadlineWinsOverStaleFinalApprovalAndCommitsItsRefund() {
    View awaiting = openResults(approve(create(alice)));
    View selected =
        tutorials.selectResult(
            bob,
            awaiting.id(),
            UUID.randomUUID(),
            new ResultInput(awaiting.rowVersion(), Result.SUCCESS));
    shiftTimes(selected.id(), now().minusSeconds(90000), now().minusSeconds(86401));
    error(
        () ->
            tutorials.finalApprove(
                bob, selected.id(), UUID.randomUUID(), new ResultInput(999, Result.SUCCESS)),
        "RESULT_CONFIRMATION_TIMEOUT");
    View expired = tutorials.get(alice, selected.id());
    assertThat(expired.status()).isEqualTo("INVALID");
    assertThat(expired.settlement().invalidReason()).isEqualTo("RESULT_CONFIRMATION_TIMEOUT");
    tutorials.processDue();
    tutorials.processDue(selected.id());
    wallet(alice, 1000, 0);
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", selected.id()))
        .isEqualTo(1);
  }

  @Test
  void cancellationNeedsTheOtherPersonAndDoesNotPauseTheDeadline() {
    View active = approve(create(alice));
    View request =
        tutorials.cancel(
            alice, active.id(), UUID.randomUUID(), new VersionInput(active.rowVersion()));
    assertThat(request.status()).isEqualTo("ACTIVE");
    wallet(alice, 900, 100);
    error(
        () ->
            tutorials.respondCancel(
                alice,
                active.id(),
                UUID.randomUUID(),
                new VersionInput(request.rowVersion()),
                true),
        "TUTORIAL_NOT_FOUND");
    View declined =
        tutorials.respondCancel(
            bob, active.id(), UUID.randomUUID(), new VersionInput(request.rowVersion()), false);
    assertThat(declined.cancelRequest().status()).isEqualTo("REJECTED");
    wallet(alice, 900, 100);
    View second =
        tutorials.cancel(
            bob, active.id(), UUID.randomUUID(), new VersionInput(declined.rowVersion()));
    View canceled =
        tutorials.respondCancel(
            alice, active.id(), UUID.randomUUID(), new VersionInput(second.rowVersion()), true);
    assertThat(canceled.status()).isEqualTo("CANCELED");
    assertThat(canceled.cancelRequest().status()).isEqualTo("CONFIRMED");
    wallet(alice, 1000, 0);
    assertThat(tutorials.overview(alice).completedAt()).isNotNull();

    View other = approve(create(bob));
    View pending =
        tutorials.cancel(bob, other.id(), UUID.randomUUID(), new VersionInput(other.rowVersion()));
    shiftTimes(other.id(), now().minusSeconds(10), now().plusSeconds(300));
    error(
        () ->
            tutorials.respondCancel(
                alice,
                other.id(),
                UUID.randomUUID(),
                new VersionInput(pending.rowVersion() + 1),
                true),
        "QUEST_VERSION_CONFLICT");
    View expired = tutorials.get(bob, other.id());
    assertThat(expired.status()).isEqualTo("ACTIVE");
    assertThat(expired.cancelRequest().status()).isEqualTo("EXPIRED");
    assertThat(expired.allowedActions()).isEmpty();
    wallet(bob, 900, 100);
    error(
        () ->
            tutorials.respondCancel(
                alice, other.id(), UUID.randomUUID(), new VersionInput(expired.rowVersion()), true),
        "CANCEL_DEADLINE_PASSED");
  }

  @Test
  void concurrentCreationsAndApprovalsAndFinalizationsRemainSingleUse() throws Exception {
    UUID key = UUID.randomUUID();
    Instant now = now();
    CreateInput input = new CreateInput(now.plusSeconds(3600), now.plusSeconds(7200));
    List<Object> created =
        race(() -> tutorials.create(alice, key, input), () -> tutorials.create(alice, key, input));
    assertThat(created.getFirst()).isEqualTo(created.getLast());
    View pending = (View) created.getFirst();
    List<Object> approved = race(() -> approve(pending), () -> approve(pending));
    assertThat(approved.stream().filter(View.class::isInstance)).hasSize(1);
    wallet(alice, 900, 100);
    View awaiting = openResults(tutorials.get(bob, pending.id()));
    View selected =
        tutorials.selectResult(
            bob,
            pending.id(),
            UUID.randomUUID(),
            new ResultInput(awaiting.rowVersion(), Result.SUCCESS));
    UUID finalKey = UUID.randomUUID();
    ResultInput finalInput = new ResultInput(selected.rowVersion(), Result.SUCCESS);
    List<Object> settled =
        race(
            () -> tutorials.finalApprove(bob, pending.id(), finalKey, finalInput),
            () -> tutorials.finalApprove(bob, pending.id(), finalKey, finalInput));
    assertThat(settled.getFirst()).isEqualTo(settled.getLast());
    wallet(alice, 1100, 0);
    assertThat(
            count(
                "SELECT count(*) FROM quests WHERE creator_id=? AND quest_type='TUTORIAL'", alice))
        .isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM monster_growth_milestone_claims WHERE monster_id=?",
                monsters.me(alice).monster().id()))
        .isEqualTo(1);
  }

  @Test
  void endedRelationshipsHideOldViewsAndRetriesAndPreservePersonalCompletion() {
    View active = approve(create(alice));
    UUID endKey = UUID.randomUUID();
    couples.end(bob, endKey, false);
    error(() -> tutorials.get(alice, active.id()), "TUTORIAL_NOT_FOUND");
    assertThat(tutorials.overview(alice).own()).isNull();
    assertThat(tutorials.overview(alice).partner()).isNull();
    cleanup.processPending();
    wallet(alice, 1000, 0);
    assertThat(tutorials.overview(alice).completedAt()).isNotNull();
    assertThat(tutorials.overview(bob).completedAt()).isNull();
    connect(alice, carol);
    assertThat(tutorials.overview(alice).own()).isNull();
    error(() -> create(alice), "TUTORIAL_ALREADY_EXISTS");
    error(() -> tutorials.get(carol, active.id()), "TUTORIAL_NOT_FOUND");
  }

  @Test
  void deadlineWorkerRetriesOneFailedWalletAndStillProcessesTheNextTutorial() {
    View first = approve(create(alice));
    View second = approve(create(bob));
    Instant time = now();
    shiftTimes(first.id(), time.minusSeconds(90000), time.minusSeconds(86401));
    shiftTimes(second.id(), time.minusSeconds(90000), time.minusSeconds(86401));
    jdbc.update("UPDATE wallets SET locked_coins=0 WHERE user_id=?", alice);

    tutorials.processDue();

    assertThat(
            jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, first.id()))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, second.id()))
        .isEqualTo("INVALID");
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", first.id()))
        .isZero();
    wallet(bob, 1000, 0);
    jdbc.update("UPDATE wallets SET locked_coins=100 WHERE user_id=?", alice);

    tutorials.processDue();
    tutorials.processDue();

    wallet(alice, 1000, 0);
    assertThat(count("SELECT count(*) FROM quest_settlements WHERE quest_id=?", first.id()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE quest_id=?", first.id()))
        .isEqualTo(2);
  }

  private UUID account(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,age_eligible_confirmed_at,signup_grant_issued_at) VALUES (?,?,'ACTIVE',now(),now())",
        id,
        name);
    jdbc.update("INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,1000,0)", id);
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'SIGNUP_GRANT',1000,0)",
        UUID.randomUUID(),
        id);
    return id;
  }

  private UUID connect(UUID inviter, UUID invitee) {
    var invite = couples.create(inviter, UUID.randomUUID());
    couples.join(invitee, invite.code());
    couples.confirm(inviter, invite.inviteId());
    return couples.confirm(invitee, invite.inviteId()).couple().id();
  }

  private View create(UUID user) {
    Instant now = now();
    return tutorials.create(
        user, UUID.randomUUID(), new CreateInput(now.plusSeconds(3600), now.plusSeconds(7200)));
  }

  private View approve(View view) {
    return tutorials.approve(
        view.creatorId().equals(alice) ? bob : alice,
        view.id(),
        UUID.randomUUID(),
        new ApproveInput(view.questVersionId(), view.rowVersion(), Result.FAILURE));
  }

  private View openResults(View view) {
    shiftTimes(view.id(), now().minusSeconds(20), now().minusSeconds(10));
    tutorials.processDue(view.id());
    return tutorials.get(view.creatorId().equals(alice) ? bob : alice, view.id());
  }

  private void shiftTimes(UUID quest, Instant due, Instant result) {
    jdbc.update(
        "UPDATE quest_versions SET due_at=?,approval_deadline_at=?,result_at=?,result_confirmation_deadline_at=? WHERE quest_id=?",
        Timestamp.from(due),
        Timestamp.from(due),
        Timestamp.from(result),
        Timestamp.from(result.plusSeconds(86400)),
        quest);
  }

  private Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  private void wallet(UUID user, long available, long locked) {
    assertThat(
            jdbc.queryForObject(
                "SELECT available_coins FROM wallets WHERE user_id=?", Long.class, user))
        .isEqualTo(available);
    assertThat(
            jdbc.queryForObject(
                "SELECT locked_coins FROM wallets WHERE user_id=?", Long.class, user))
        .isEqualTo(locked);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private static void error(Runnable call, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).errorCode()).isEqualTo(code));
  }

  private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
    CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(() -> outcome(first, ready, start));
      var right = executor.submit(() -> outcome(second, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
    }
  }

  private static Object outcome(Callable<?> action, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Concurrent test did not start");
    try {
      return action.call();
    } catch (ApiException error) {
      return error;
    }
  }
}
