package com.betchu.backend.quests;

import static org.assertj.core.api.Assertions.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.couples.CoupleService;
import com.betchu.backend.couples.RelationshipCleanupService;
import com.betchu.backend.quests.QuestModels.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@TestPropertySource(
    properties = {
      "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "betchu.couples.cleanup-delay-ms=3600000",
      "betchu.couples.retention-delay-ms=3600000"
    })
class QuestDraftIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired QuestService quests;
  @Autowired CoupleService couples;
  @Autowired RelationshipCleanupService cleanup;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager manager;
  UUID alice, bob, carol, dana, relationship;
  String failureHook;

  @BeforeEach
  void setupOwnFixtures() {
    alice = account("Alice", true);
    bob = account("Bob", true);
    carol = account("Carol", true);
    dana = account("Dana", false);
    relationship = connect(alice, bob);
  }

  @AfterEach
  void removeOnlyOwnFixtures() {
    removeFailureHook();
    Object[] users = {alice, bob, carol, dana};
    new TransactionTemplate(manager)
        .executeWithoutResult(
            tx -> {
              jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
              jdbc.update(
                  "DELETE FROM relationship_end_job_items WHERE resource_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?,?))",
                  users);
              jdbc.update("DELETE FROM quest_request_receipts WHERE user_id IN (?,?,?,?)", users);
              jdbc.update(
                  "DELETE FROM quest_drafts WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?,?))",
                  users);
              jdbc.update("DELETE FROM quests WHERE creator_id IN (?,?,?,?)", users);
              jdbc.update(
                  "DELETE FROM starter_creation_receipts WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM user_active_monsters WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM monsters WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM user_progressions WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM wallet_ledger WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM wallets WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM couple_action_receipts WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM couple_code_attempts WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM couple_pairing_slots WHERE user_id IN (?,?,?,?)", users);
              jdbc.update(
                  "DELETE FROM relationship_end_jobs WHERE initiated_by IN (?,?,?,?)", users);
              List<UUID> ids =
                  jdbc.queryForList(
                      "SELECT DISTINCT couple_id FROM couple_members WHERE user_id IN (?,?,?,?)",
                      UUID.class,
                      users);
              jdbc.update("DELETE FROM couple_members WHERE user_id IN (?,?,?,?)", users);
              for (UUID id : ids) jdbc.update("DELETE FROM couples WHERE id=?", id);
              jdbc.update("DELETE FROM couple_invites WHERE inviter_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM users WHERE id IN (?,?,?,?)", users);
            });
  }

  @Test
  void createReadEditDiscardNeverReserveCoinsOrGrowthAndReceiptCannotResurrectText() {
    UUID key = UUID.randomUUID();
    DraftInput input = input("\u2000별 약속\u00a0");
    DraftView created = quests.create(alice, key, input);
    assertThat(created.title()).isEqualTo("별 약속");
    assertThat(created.status()).isEqualTo("DRAFT");
    assertThat(created.rowVersion()).isZero();
    assertThat(created.approvalDeadlineAt()).isEqualTo(created.dueAt().minusSeconds(1800));
    assertThat(created.resultConfirmationDeadlineAt())
        .isEqualTo(created.resultAt().plusSeconds(86400));
    assertThat(
            jdbc.queryForObject(
                "SELECT tutorial_completed_at FROM users WHERE id=?", Timestamp.class, alice))
        .isNull();
    DraftView edited = quests.update(alice, created.id(), 0, input("new title"));
    assertThat(edited.rowVersion()).isEqualTo(1);
    assertThat(quests.create(alice, key, input)).isEqualTo(created);
    assertThat(quests.get(alice, created.id()).title()).isEqualTo("new title");
    error(() -> quests.create(alice, key, input("changed")), "IDEMPOTENCY_KEY_REUSED");
    UUID discardKey = UUID.randomUUID();
    DiscardResult discarded = quests.discard(alice, created.id(), 1, discardKey);
    assertThat(discarded.rowVersion()).isEqualTo(2);
    assertThat(quests.discard(alice, created.id(), 1, discardKey)).isEqualTo(discarded);
    assertThat(count("SELECT count(*) FROM quest_drafts WHERE quest_id=?", created.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT response_json FROM quest_request_receipts WHERE user_id=? AND idempotency_key=?",
                String.class,
                alice,
                key))
        .isNull();
    error(() -> quests.create(alice, key, input), "QUEST_NOT_FOUND");
    error(() -> quests.get(alice, created.id()), "QUEST_NOT_FOUND");
    assertThat(quests.list(alice, "DRAFT", 20, null).quests()).isEmpty();
    unchanged(alice);
    unchanged(bob);
  }

  @Test
  void authorPrivacyCoversAllQueriesAndMutationEndpoints() {
    DraftView draft = quests.create(alice, UUID.randomUUID(), input("private draft"));
    assertThat(quests.list(bob, "DRAFT", 20, null).quests()).isEmpty();
    for (UUID other : List.of(bob, carol)) {
      error(() -> quests.get(other, draft.id()), "QUEST_NOT_FOUND");
      error(() -> quests.update(other, draft.id(), 0, input("intrusion")), "QUEST_NOT_FOUND");
      error(() -> quests.discard(other, draft.id(), 0, UUID.randomUUID()), "QUEST_NOT_FOUND");
    }
    assertThat(quests.get(alice, draft.id())).isEqualTo(draft);
  }

  @Test
  void optimisticConcurrencyAllowsExactlyOneEditAndStaleDiscardLeavesTextIntact() throws Exception {
    DraftView draft = quests.create(alice, UUID.randomUUID(), input("initial"));
    List<Object> results =
        race(
            () -> quests.update(alice, draft.id(), 0, input("left")),
            () -> quests.update(alice, draft.id(), 0, input("right")));
    assertThat(results.stream().filter(DraftView.class::isInstance)).hasSize(1);
    assertThat(
            results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .map(ApiException::errorCode))
        .containsExactly("QUEST_VERSION_CONFLICT");
    error(() -> quests.discard(alice, draft.id(), 0, UUID.randomUUID()), "QUEST_VERSION_CONFLICT");
    assertThat(quests.get(alice, draft.id()).rowVersion()).isEqualTo(1);
    unchanged(alice);
  }

  @Test
  void simultaneousCreateWithOneKeyMakesOneDraftAndKeysAreActionBound() throws Exception {
    UUID key = UUID.randomUUID();
    List<Object> responses =
        race(
            () -> quests.create(alice, key, input("same")),
            () -> quests.create(alice, key, input("same")));
    assertThat(responses.get(0)).isEqualTo(responses.get(1));
    DraftView draft = (DraftView) responses.get(0);
    assertThat(count("SELECT count(*) FROM quests WHERE creator_id=?", alice)).isEqualTo(1);
    error(() -> quests.discard(alice, draft.id(), 0, key), "IDEMPOTENCY_KEY_REUSED");
    UUID discardKey = UUID.randomUUID();
    quests.discard(alice, draft.id(), 0, discardKey);
    error(() -> quests.create(alice, discardKey, input("other")), "IDEMPOTENCY_KEY_REUSED");
  }

  @Test
  void keysetPaginationIsStableForTiedTimesAndBoundedHostileCursorsAreRejected() {
    Set<UUID> expected = new HashSet<>();
    for (int i = 0; i < 5; i++)
      expected.add(quests.create(alice, UUID.randomUUID(), input("draft " + i)).id());
    jdbc.update("UPDATE quests SET created_at='2026-09-07T00:00:00Z' WHERE creator_id=?", alice);
    Set<UUID> seen = new HashSet<>();
    String cursor = null;
    do {
      DraftPage page = quests.list(alice, "DRAFT", 2, cursor);
      assertThat(page.quests()).hasSizeLessThanOrEqualTo(2);
      for (DraftView draft : page.quests()) assertThat(seen.add(draft.id())).isTrue();
      cursor = page.nextCursor();
    } while (cursor != null);
    assertThat(seen).isEqualTo(expected);
    for (String bad :
        List.of(
            "garbage",
            "x".repeat(257),
            encoded("+1000000000-12-31T23:59:59Z"),
            encoded("-1000000000-01-01T00:00:00Z"),
            encoded("1999-12-31T00:00:00Z"),
            encoded("2101-01-01T00:00:00Z")))
      error(() -> quests.list(alice, "DRAFT", 20, bad), "INVALID_QUEST_REQUEST");
    error(() -> quests.list(alice, "ACTIVE", 20, null), "INVALID_QUEST_REQUEST");
    error(() -> quests.list(alice, "DRAFT", 51, null), "INVALID_QUEST_REQUEST");
  }

  @Test
  void accountRelationshipAndStarterAreRequiredButTutorialAndBalanceAreNot() {
    error(() -> quests.create(carol, UUID.randomUUID(), input("no couple")), "COUPLE_REQUIRED");
    connect(carol, dana);
    error(() -> quests.create(dana, UUID.randomUUID(), input("no starter")), "STARTER_REQUIRED");
    jdbc.update("UPDATE users SET status='PENDING_ELIGIBILITY' WHERE id=?", alice);
    error(() -> quests.create(alice, UUID.randomUUID(), input("inactive")), "ACCOUNT_NOT_ACTIVE");
    jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", alice);
    jdbc.update("UPDATE wallets SET available_coins=0 WHERE user_id=?", alice);
    assertThat(quests.create(alice, UUID.randomUUID(), input("save 100C proposal")).stake())
        .isEqualTo(100);
    assertThat(count("SELECT locked_coins FROM wallets WHERE user_id=?", alice)).isZero();
  }

  @Test
  void safetyCommitSurvivesCleanupFailureAndCountsOnlyCallersPrivateDrafts() {
    DraftView mine = quests.create(alice, UUID.randomUUID(), input("my private draft"));
    DraftView theirs = quests.create(bob, UUID.randomUUID(), input("their private draft"));
    UUID key = UUID.randomUUID();
    assertThat(couples.end(alice, key, false).status()).isEqualTo("PROCESSING");
    UUID job = couples.endStatus(alice).job().id();
    UUID partnerEndKey = UUID.randomUUID();
    assertThat(couples.end(bob, partnerEndKey, false).status()).isEqualTo("PROCESSING");
    assertThat(couples.endStatus(alice).job().targetResourceCount()).isEqualTo(1);
    assertThat(couples.endStatus(bob).job().targetResourceCount()).isEqualTo(1);
    assertThat(couples.endStatus(carol).job()).isNull();
    assertThat(count("SELECT target_resource_count FROM relationship_end_jobs WHERE id=?", job))
        .isEqualTo(2);
    installFailureHook(mine.id());
    assertThatThrownBy(() -> cleanup.processItem(job, mine.id()))
        .isInstanceOf(DataAccessException.class);
    assertThat(couples.me(alice).couple()).isNull();
    assertThat(couples.me(bob).couple()).isNull();
    assertThat(jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, mine.id()))
        .isEqualTo("DRAFT");
    assertThat(count("SELECT row_version FROM quests WHERE id=?", mine.id())).isZero();
    error(() -> quests.get(alice, mine.id()), "QUEST_NOT_FOUND");
    error(() -> quests.update(alice, mine.id(), 0, input("after end")), "QUEST_NOT_FOUND");
    error(() -> couples.create(alice, UUID.randomUUID()), "PAIRING_CONFLICT");
    cleanup.processItem(job, theirs.id());
    assertThat(couples.endStatus(alice).job().processedResourceCount()).isZero();
    assertThat(couples.endStatus(bob).job().processedResourceCount()).isEqualTo(1);
    assertThat(couples.end(alice, key, false).status()).isEqualTo("PROCESSING");
    removeFailureHook();
    cleanup.processItem(job, mine.id());
    cleanup.processItem(job, mine.id());
    assertThat(couples.end(alice, key, false).status()).isEqualTo("COMPLETED");
    assertThat(couples.end(bob, partnerEndKey, false).status()).isEqualTo("COMPLETED");
    assertThat(count("SELECT row_version FROM quests WHERE id=?", mine.id())).isEqualTo(1);
    assertThat(
            count(
                "SELECT count(*) FROM quest_drafts WHERE quest_id IN (?,?)",
                mine.id(),
                theirs.id()))
        .isEqualTo(2);
    unchanged(alice);
    unchanged(bob);
    connect(alice, carol);
    UUID current = couples.me(alice).couple().id();
    couples.end(alice, key, false);
    assertThat(couples.me(alice).couple().id()).isEqualTo(current);
    error(() -> quests.get(alice, mine.id()), "QUEST_NOT_FOUND");
    assertThat(quests.list(alice, "DRAFT", 20, null).quests()).isEmpty();
  }

  @Test
  void workerRetriesFailedItemsWithoutDroppingOtherItemsAndPurgesOnlyExpiredCanceledText() {
    UUID createKey = UUID.randomUUID();
    DraftView first = quests.create(alice, createKey, input("private retained text"));
    DraftView second = quests.create(bob, UUID.randomUUID(), input("second"));
    couples.end(alice, UUID.randomUUID(), true);
    UUID job = couples.endStatus(alice).job().id();
    installFailureHook(first.id());
    cleanup.processPending();
    assertThat(count("SELECT processed_resource_count FROM relationship_end_jobs WHERE id=?", job))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, second.id()))
        .isEqualTo("CANCELED_RELATIONSHIP_ENDED");
    removeFailureHook();
    cleanup.processPending();
    cleanup.processPending();
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("COMPLETED");
    cleanup.purgeExpiredDraftText();
    assertThat(count("SELECT count(*) FROM quest_drafts WHERE quest_id=?", first.id()))
        .isEqualTo(1);
    jdbc.update(
        "UPDATE couples SET ended_at=clock_timestamp()-interval '31 days' WHERE id=?",
        relationship);
    cleanup.purgeExpiredDraftText();
    cleanup.purgeExpiredDraftText();
    assertThat(
            count(
                "SELECT count(*) FROM quest_drafts WHERE quest_id IN (?,?)",
                first.id(),
                second.id()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT response_json FROM quest_request_receipts WHERE user_id=? AND idempotency_key=?",
                String.class,
                alice,
                createKey))
        .isNull();
    assertThat(
            count(
                "SELECT count(*) FROM user_blocks WHERE blocker_id=? AND blocked_id=?", alice, bob))
        .isEqualTo(1);
    unchanged(alice);
    unchanged(bob);
  }

  @Test
  void retentionDeadlineDoesNotWaitForSuccessfulCancellation() {
    UUID key = UUID.randomUUID();
    DraftView draft = quests.create(alice, key, input("private text awaiting failed cleanup"));
    couples.end(alice, UUID.randomUUID(), false);
    UUID job = couples.endStatus(alice).job().id();
    installFailureHook(draft.id());
    assertThatThrownBy(() -> cleanup.processItem(job, draft.id()))
        .isInstanceOf(DataAccessException.class);
    jdbc.update(
        "UPDATE couples SET ended_at=clock_timestamp()-interval '31 days' WHERE id=?",
        relationship);
    cleanup.purgeExpiredDraftText();
    assertThat(count("SELECT count(*) FROM quest_drafts WHERE quest_id=?", draft.id())).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT response_json FROM quest_request_receipts WHERE user_id=? AND idempotency_key=?",
                String.class,
                alice,
                key))
        .isNull();
    assertThat(
            jdbc.queryForObject("SELECT status FROM quests WHERE id=?", String.class, draft.id()))
        .isEqualTo("DRAFT");
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("PROCESSING");
    removeFailureHook();
    cleanup.processItem(job, draft.id());
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("COMPLETED");
    assertThat(count("SELECT row_version FROM quests WHERE id=?", draft.id())).isEqualTo(1);
    unchanged(alice);
  }

  @Test
  void racingEndAndCreateNeverLeaveAnUnsnapshottedDraft() throws Exception {
    List<Object> outcomes =
        race(
            () -> quests.create(alice, UUID.randomUUID(), input("racing draft")),
            () -> couples.end(bob, UUID.randomUUID(), false));
    assertThat(
            outcomes.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .map(ApiException::errorCode))
        .allMatch("COUPLE_REQUIRED"::equals);
    cleanup.processPending();
    assertThat(
            count("SELECT count(*) FROM quests WHERE couple_id=? AND status='DRAFT'", relationship))
        .isZero();
    assertThat(couples.endStatus(alice).job().status()).isEqualTo("COMPLETED");
    unchanged(alice);
  }

  private UUID account(String nickname, boolean monster) {
    UUID user = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,age_eligible_confirmed_at) VALUES (?,?,'ACTIVE',now())",
        user,
        nickname);
    jdbc.update(
        "INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,1000,0)", user);
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'SIGNUP_GRANT',1000,0)",
        UUID.randomUUID(),
        user);
    if (monster) {
      UUID id = UUID.randomUUID();
      jdbc.update("INSERT INTO user_progressions(user_id) VALUES (?)", user);
      jdbc.update(
          "INSERT INTO monsters(id,user_id,name,species,acquisition_source) VALUES (?,?,'별이','STARLIGHT','STARTER')",
          id,
          user);
      jdbc.update("INSERT INTO user_active_monsters(user_id,monster_id) VALUES (?,?)", user, id);
    }
    return user;
  }

  private UUID connect(UUID left, UUID right) {
    var invite = couples.create(left, UUID.randomUUID());
    couples.join(right, invite.code());
    couples.confirm(left, invite.inviteId());
    return couples.confirm(right, invite.inviteId()).couple().id();
  }

  private void unchanged(UUID user) {
    assertThat(count("SELECT available_coins FROM wallets WHERE user_id=?", user)).isEqualTo(1000);
    assertThat(count("SELECT locked_coins FROM wallets WHERE user_id=?", user)).isZero();
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE user_id=?", user)).isEqualTo(1);
    assertThat(count("SELECT current_level_exp FROM user_progressions WHERE user_id=?", user))
        .isZero();
    assertThat(count("SELECT recognized_success_count FROM monsters WHERE user_id=?", user))
        .isZero();
    assertThat(count("SELECT streak FROM user_progressions WHERE user_id=?", user)).isZero();
  }

  private void installFailureHook(UUID quest) {
    failureHook = "test_cleanup_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION "
            + failureHook
            + "() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''Test cleanup failure''; END;'");
    jdbc.execute(
        "CREATE TRIGGER "
            + failureHook
            + " BEFORE UPDATE ON relationship_end_job_items FOR EACH ROW WHEN (OLD.resource_id='"
            + quest
            + "'::uuid) EXECUTE FUNCTION "
            + failureHook
            + "()");
  }

  private void removeFailureHook() {
    if (failureHook == null) return;
    jdbc.execute("DROP TRIGGER IF EXISTS " + failureHook + " ON relationship_end_job_items");
    jdbc.execute("DROP FUNCTION IF EXISTS " + failureHook + "()");
    failureHook = null;
  }

  private static DraftInput input(String title) {
    return new DraftInput(
        title,
        Category.CUSTOM,
        "first condition\nsecond condition",
        4,
        100,
        Instant.parse("2026-09-07T10:00:00Z"),
        Instant.parse("2026-09-07T11:00:00Z"),
        30,
        "NONE");
  }

  private int count(String sql, Object... params) {
    return jdbc.queryForObject(sql, Integer.class, params);
  }

  private static String encoded(String time) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((time + "|" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
  }

  private static void error(Runnable action, String code) {
    assertThatThrownBy(action::run)
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).errorCode()).isEqualTo(code));
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
    } catch (ApiException error) {
      return error;
    }
  }
}
