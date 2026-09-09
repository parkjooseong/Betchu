package com.betchu.backend.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.common.GameAccess;
import com.betchu.backend.couples.CoupleService;
import com.betchu.backend.game.GameModels.GrowthStage;
import com.betchu.backend.game.GameModels.Milestone;
import com.betchu.backend.game.GameModels.MonsterView;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import com.betchu.backend.starter.StarterModels.Species;
import com.betchu.backend.starter.StarterService;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@TestPropertySource(
    properties = "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class MonsterProgressionIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired MonsterService monsters;
  @Autowired HomeService home;
  @Autowired CoupleService couples;
  @Autowired GameAccess access;
  @Autowired StarterService starters;
  @Autowired JdbcTemplate jdbc;
  @Autowired JsonMapper mapper;
  @Autowired PlatformTransactionManager transactionManager;
  UUID alice;
  UUID bob;
  UUID carol;

  @BeforeEach
  void createOnlyThisTestsAccounts() {
    alice = account("Alice");
    bob = account("Bob");
    carol = account("Carol");
  }

  @AfterEach
  void removeOnlyThisTestsFixtures() {
    Object[] users = {alice, bob, carol};
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
              jdbc.update(
                  "DELETE FROM relationship_end_job_items WHERE resource_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update("DELETE FROM quest_request_receipts WHERE user_id IN (?,?,?)", users);
              jdbc.update(
                  "DELETE FROM quest_drafts WHERE quest_id IN (SELECT id FROM quests WHERE creator_id IN (?,?,?))",
                  users);
              jdbc.update("DELETE FROM quests WHERE creator_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM starter_creation_receipts WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM user_active_monsters WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM monsters WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM user_progressions WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM wallet_ledger WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM wallets WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM couple_action_receipts WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM couple_code_attempts WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM couple_pairing_slots WHERE user_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM relationship_end_jobs WHERE initiated_by IN (?,?,?)", users);
              List<UUID> ids =
                  jdbc.queryForList(
                      "SELECT DISTINCT couple_id FROM couple_members WHERE user_id IN (?,?,?)",
                      UUID.class,
                      users);
              jdbc.update("DELETE FROM couple_members WHERE user_id IN (?,?,?)", users);
              for (UUID id : ids) jdbc.update("DELETE FROM couples WHERE id=?", id);
              jdbc.update("DELETE FROM couple_invites WHERE inviter_id IN (?,?,?)", users);
              jdbc.update("DELETE FROM users WHERE id IN (?,?,?)", users);
            });
  }

  @Test
  void starterProgressionAndActiveReferenceAreCreatedWithoutAnotherGrant() {
    connect(alice, bob);
    MonsterView created = create(alice, UUID.randomUUID(), "\u0085\u00a0별이\u2003");
    assertThat(created.name()).isEqualTo("별이");
    assertThat(created.growthStage()).isEqualTo(GrowthStage.EGG);
    assertThat(created.nextGrowthMilestone()).isEqualTo(Milestone.HATCH);
    assertThat(created.reachedMilestones()).isEmpty();
    assertThat(created.masteryRewards()).isEmpty();
    assertThat(created.recognizedSuccessCount()).isZero();
    assertThat(created.accountLevel()).isEqualTo(1);
    assertThat(created.currentLevelExp()).isZero();
    assertThat(created.nextLevelRequiredExp()).isEqualTo(80);
    assertThat(created.baseHp()).isEqualTo(100);
    assertThat(created.baseAttack()).isEqualTo(10);
    assertThat(created.equipmentBonusHp()).isZero();
    assertThat(created.equipmentBonusAttack()).isZero();
    assertThat(created.finalHp()).isEqualTo(100);
    assertThat(created.finalAttack()).isEqualTo(10);
    assertThat(created.combatPower()).isEqualTo(700);
    assertThat(created.streak()).isZero();
    assertThat(monsters.me(alice).monster()).isEqualTo(created);
    assertThat(monsters.partner(bob).monster()).isEqualTo(created);
    assertThat(count("SELECT count(*) FROM user_progressions WHERE user_id=?", alice)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM user_active_monsters WHERE user_id=?", alice))
        .isEqualTo(1);
    assertWalletUnchanged(alice);
  }

  @Test
  void retriesUseNormalizedInputAndPreserveTheOriginalCreationResponseAfterRename() {
    connect(alice, bob);
    UUID key = UUID.randomUUID();
    MonsterView first = create(alice, key, " 별이 ");
    assertThat(create(alice, key, "별이")).isEqualTo(first);
    assertThat(monsters.rename(alice, "새이름").name()).isEqualTo("새이름");
    assertThat(create(alice, key, "별이")).isEqualTo(first);
    assertThat(monsters.me(alice).monster().name()).isEqualTo("새이름");
    assertError(() -> create(alice, key, "다른이름"), "IDEMPOTENCY_KEY_REUSED");
    assertError(
        () -> monsters.create(alice, key, new PreviewRequest(Species.WAVE, "별이")),
        "IDEMPOTENCY_KEY_REUSED");
    assertError(() -> create(alice, UUID.randomUUID(), "별이"), "STARTER_ALREADY_EXISTS");
    assertWalletUnchanged(alice);
  }

  @Test
  void concurrentSameKeyCreatesOneMonsterAndReplaysExactlyTheSameProjection() throws Exception {
    connect(alice, bob);
    UUID key = UUID.randomUUID();
    List<Object> results = race(() -> create(alice, key, "별이"), () -> create(alice, key, " 별이 "));
    assertThat(results.get(0)).isInstanceOf(MonsterView.class).isEqualTo(results.get(1));
    assertThat(count("SELECT count(*) FROM monsters WHERE user_id=?", alice)).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM starter_creation_receipts WHERE user_id=?", alice))
        .isEqualTo(1);
    assertWalletUnchanged(alice);
  }

  @Test
  void concurrentDifferentKeysCannotCreateASecondStarter() throws Exception {
    connect(alice, bob);
    List<Object> results =
        race(
            () -> create(alice, UUID.randomUUID(), "별이"),
            () -> create(alice, UUID.randomUUID(), "다른별"));
    assertThat(results.stream().filter(MonsterView.class::isInstance)).hasSize(1);
    assertThat(
            results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .map(ApiException::errorCode))
        .containsExactly("STARTER_ALREADY_EXISTS");
    assertThat(count("SELECT count(*) FROM monsters WHERE user_id=?", alice)).isEqualTo(1);
  }

  @Test
  void endingAndReconnectingPreservesPersonalAssetsAndRevokesOldPartnerAccess() {
    connect(alice, bob);
    UUID creationKey = UUID.randomUUID();
    MonsterView first = create(alice, creationKey, "별이");
    create(bob, UUID.randomUUID(), "파도");
    couples.end(alice, UUID.randomUUID(), false);
    assertThat(monsters.me(alice).monster()).isEqualTo(first);
    assertThat(create(alice, creationKey, "별이")).isEqualTo(first);
    assertThat(home.home(alice).coupleId()).isNull();
    assertThat(home.home(alice).partner()).isNull();
    assertThat(home.home(alice).self().monster()).isEqualTo(first);
    assertError(() -> monsters.partner(bob), "COUPLE_REQUIRED");
    MonsterView renamed = monsters.rename(alice, "함께별");
    connect(alice, carol);
    assertThat(monsters.partner(carol).monster()).isEqualTo(renamed);
    assertThat(monsters.partner(alice).monster()).isNull();
    assertThat(monsters.me(bob).monster().name()).isEqualTo("파도");
    assertError(() -> create(alice, UUID.randomUUID(), "다른별"), "STARTER_ALREADY_EXISTS");
    assertWalletUnchanged(alice);
    assertWalletUnchanged(bob);
  }

  @Test
  void aConcurrentEndAndCreationCannotWriteIntoAnEndedRelationship() throws Exception {
    connect(alice, bob);
    List<Object> results =
        race(
            () -> create(alice, UUID.randomUUID(), "별이"),
            () -> couples.end(bob, UUID.randomUUID(), false));
    if (results.getFirst() instanceof ApiException error) {
      assertThat(error.errorCode()).isEqualTo("COUPLE_REQUIRED");
      assertThat(monsters.me(alice).monster()).isNull();
    } else {
      assertThat(monsters.me(alice).monster()).isEqualTo(results.getFirst());
    }
    assertThat(home.home(alice).coupleId()).isNull();
    assertError(() -> monsters.partner(alice), "COUPLE_REQUIRED");
    assertWalletUnchanged(alice);
  }

  @Test
  void inactiveBlockedAndIncompleteRelationshipsCannotCreateOrExposeAPartner() {
    assertThat(monsters.me(alice).monster()).isNull();
    assertThat(home.home(alice).self().availableCoins()).isEqualTo(1000);
    assertError(() -> create(alice, UUID.randomUUID(), "별이"), "COUPLE_REQUIRED");
    assertError(() -> monsters.rename(alice, "별이"), "MONSTER_NOT_FOUND");
    connect(alice, bob);
    jdbc.update(
        "INSERT INTO user_blocks(blocker_id,blocked_id,created_at) VALUES (?,?,now())", bob, alice);
    assertError(() -> create(alice, UUID.randomUUID(), "별이"), "COUPLE_REQUIRED");
    assertThat(home.home(alice).partner()).isNull();
    jdbc.update("DELETE FROM user_blocks WHERE blocker_id=? AND blocked_id=?", bob, alice);
    jdbc.update("UPDATE users SET status='DELETION_PENDING' WHERE id=?", bob);
    assertError(() -> monsters.partner(alice), "COUPLE_REQUIRED");
    assertThat(home.home(alice).partner()).isNull();
    assertError(() -> monsters.me(bob), "ACCOUNT_NOT_ACTIVE");
    assertError(() -> create(bob, UUID.randomUUID(), "별이"), "ACCOUNT_NOT_ACTIVE");
    assertThat(count("SELECT count(*) FROM user_progressions WHERE user_id IN (?,?)", alice, bob))
        .isZero();
  }

  @Test
  void receiptFailureRollsBackMonsterProgressionAndActiveReferenceTogether() {
    connect(alice, bob);
    JsonMapper brokenMapper = mock(JsonMapper.class);
    when(brokenMapper.writeValueAsString(any()))
        .thenThrow(new IllegalStateException("Serialization failed"));
    MonsterService failing = new MonsterService(jdbc, access, starters, brokenMapper);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .execute(
                        status ->
                            failing.create(
                                alice,
                                UUID.randomUUID(),
                                new PreviewRequest(Species.STARLIGHT, "별이"))))
        .isInstanceOf(IllegalStateException.class);
    assertThat(count("SELECT count(*) FROM user_progressions WHERE user_id=?", alice)).isZero();
    assertThat(count("SELECT count(*) FROM monsters WHERE user_id=?", alice)).isZero();
    assertThat(count("SELECT count(*) FROM user_active_monsters WHERE user_id=?", alice)).isZero();
    assertThat(count("SELECT count(*) FROM starter_creation_receipts WHERE user_id=?", alice))
        .isZero();
    assertThat(create(alice, UUID.randomUUID(), "별이").name()).isEqualTo("별이");
    assertWalletUnchanged(alice);
  }

  @Test
  void databaseOwnershipConstraintRejectsAnActiveReferenceToAnotherPersonsMonster() {
    connect(alice, bob);
    MonsterView first = create(alice, UUID.randomUUID(), "별이");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO user_active_monsters(user_id,monster_id) VALUES (?,?)",
                    bob,
                    first.id()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(monsters.me(bob).monster()).isNull();
    assertThat(monsters.me(alice).monster()).isEqualTo(first);
  }

  @Test
  void unicodeNamesCountCodePointsAndInvalidRenamesLeaveThePreviousNameUntouched() {
    connect(alice, bob);
    String emoji = "🐣".repeat(10);
    assertThat(create(alice, UUID.randomUUID(), emoji).name()).isEqualTo(emoji);
    for (String invalid :
        List.of("🐣".repeat(11), "a\u2028b", "a\u2029b", "a\u200Db", "a\u0000b", "\u00a0")) {
      assertError(() -> monsters.rename(alice, invalid), "INVALID_STARTER_SELECTION");
      assertThat(monsters.me(alice).monster().name()).isEqualTo(emoji);
    }
    assertThat(monsters.rename(alice, " \u00a0별이\u0085").name()).isEqualTo("별이");
    assertWalletUnchanged(alice);
  }

  @Test
  void homeShowsOnlyOwnWalletAndCurrentAuthorDraftCount() {
    UUID coupleId = connect(alice, bob);
    MonsterView self = create(alice, UUID.randomUUID(), "별이");
    MonsterView partner = create(bob, UUID.randomUUID(), "파도");
    jdbc.update("UPDATE wallets SET available_coins=250,locked_coins=750 WHERE user_id=?", bob);
    draft(alice, coupleId, "Private Alice draft");
    draft(bob, coupleId, "Private Bob draft one");
    draft(bob, coupleId, "Private Bob draft two");
    var result = home.home(alice);
    assertThat(result.serverTime()).isNotNull();
    assertThat(result.coupleId()).isEqualTo(coupleId);
    assertThat(result.self().monster()).isEqualTo(self);
    assertThat(result.self().availableCoins()).isEqualTo(1000);
    assertThat(result.self().lockedCoins()).isZero();
    assertThat(result.partner().id()).isEqualTo(bob);
    assertThat(result.partner().monster()).isEqualTo(partner);
    assertThat(result.ownDraftCount()).isEqualTo(1);
    assertThat(home.home(bob).ownDraftCount()).isEqualTo(2);
    assertThat(result.questStatusSummary()).isEqualTo(new GameModels.QuestStatusSummary(0, 0, 0));
    var json = mapper.valueToTree(result);
    assertThat(json.path("partner").has("availableCoins")).isFalse();
    assertThat(json.path("partner").has("lockedCoins")).isFalse();
    assertThat(json.path("partner").has("ownDraftCount")).isFalse();
    assertThat(mapper.writeValueAsString(result)).doesNotContain("Private Alice", "Private Bob");
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

  private MonsterView create(UUID owner, UUID key, String name) {
    return monsters.create(owner, key, new PreviewRequest(Species.STARLIGHT, name));
  }

  private void draft(UUID owner, UUID couple, String title) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO quests(id,couple_id,creator_id,quest_type,source_type,status,created_at) VALUES (?,?,?,'PERSONAL','CUSTOM','DRAFT',now())",
        id,
        couple,
        owner);
    jdbc.update(
        """
        INSERT INTO quest_drafts(quest_id,title,category,success_criteria,difficulty,stake,due_at,result_at,minimum_duration_minutes,evidence_method,revision_reason,updated_at)
        VALUES (?,?,'STUDY','Read one page',1,0,now()+interval '1 day',now()+interval '2 days',0,'NONE','INITIAL',now())
        """,
        id,
        title);
  }

  private void assertWalletUnchanged(UUID userId) {
    assertThat(
            jdbc.queryForObject(
                "SELECT available_coins FROM wallets WHERE user_id=?", Long.class, userId))
        .isEqualTo(1000);
    assertThat(
            jdbc.queryForObject(
                "SELECT locked_coins FROM wallets WHERE user_id=?", Long.class, userId))
        .isZero();
    assertThat(count("SELECT count(*) FROM wallet_ledger WHERE user_id=?", userId)).isEqualTo(1);
  }

  private int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private static void assertError(Runnable call, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).errorCode()).isEqualTo(code));
  }

  private static List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left = executor.submit(() -> outcome(first, ready, start));
      var right = executor.submit(() -> outcome(second, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
    }
  }

  private static Object outcome(Callable<?> call, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Concurrent test did not start");
    try {
      return call.call();
    } catch (ApiException error) {
      return error;
    }
  }
}
