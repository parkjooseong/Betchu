package com.betchu.backend.couples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.couples.CoupleModels.CreatedInvite;
import com.betchu.backend.couples.CoupleModels.State;
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

@TestPropertySource(
    properties = "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class CouplePairingIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired CoupleService service;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired InviteCodeCodec codec;
  UUID alice;
  UUID bob;
  UUID carol;
  UUID dana;

  @BeforeEach
  void createOnlyThisTestsAccounts() {
    alice = account("Alice");
    bob = account("Bob");
    carol = account("Carol");
    dana = account("Dana");
  }

  @AfterEach
  void removeOnlyThisTestsFixtures() {
    Object[] users = {alice, bob, carol, dana};
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              jdbc.execute("SELECT pg_advisory_xact_lock(477529190429401)");
              jdbc.update("DELETE FROM couple_action_receipts WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM couple_code_attempts WHERE user_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM user_blocks WHERE blocker_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM couple_pairing_slots WHERE user_id IN (?,?,?,?)", users);
              jdbc.update(
                  "DELETE FROM relationship_end_jobs WHERE initiated_by IN (?,?,?,?)", users);
              List<UUID> couples =
                  jdbc.queryForList(
                      "SELECT DISTINCT couple_id FROM couple_members WHERE user_id IN (?,?,?,?)",
                      UUID.class,
                      users);
              jdbc.update("DELETE FROM couple_members WHERE user_id IN (?,?,?,?)", users);
              for (UUID couple : couples) jdbc.update("DELETE FROM couples WHERE id = ?", couple);
              jdbc.update("DELETE FROM couple_invites WHERE inviter_id IN (?,?,?,?)", users);
              jdbc.update("DELETE FROM users WHERE id IN (?,?,?,?)", users);
            });
  }

  @Test
  void codeIsRevealedOnceAndBothIndependentConfirmationsAreRequired() {
    assertThat(service.me(alice)).isEqualTo(new State(null, null));
    UUID key = UUID.randomUUID();
    CreatedInvite invite = service.create(alice, key);
    assertThat(invite.code()).isNotBlank();
    assertThat(
            jdbc.queryForObject(
                "SELECT code_hmac FROM couple_invites WHERE id = ?",
                String.class,
                invite.inviteId()))
        .isEqualTo(codec.hash(invite.code()))
        .doesNotContain(invite.code());
    assertThat(service.create(alice, key))
        .isEqualTo(new CreatedInvite(invite.inviteId(), null, invite.expiresAt()));
    assertThat(service.preview(bob, invite.code()).inviter().nickname()).isEqualTo("Alice");
    var pending = service.join(bob, invite.code());
    assertThat(pending.role()).isEqualTo("INVITEE");
    assertThat(pending.myConfirmed()).isFalse();
    assertThat(pending.partnerConfirmed()).isFalse();
    assertThat(service.join(bob, invite.code())).isEqualTo(pending);
    assertThat(service.me(alice).couple()).isNull();
    State first = service.confirm(alice, invite.inviteId());
    assertThat(first.couple()).isNull();
    assertThat(first.pendingInvite().myConfirmed()).isTrue();
    State connected = service.confirm(bob, invite.inviteId());
    assertThat(connected.couple().partner().id()).isEqualTo(alice);
    assertThat(connected.pendingInvite()).isNull();
    assertThat(service.me(alice).couple().id()).isEqualTo(connected.couple().id());
    assertThat(service.confirm(bob, invite.inviteId()).couple().id())
        .isEqualTo(connected.couple().id());
    assertThat(
            count(
                "SELECT count(*) FROM couple_members WHERE couple_id = ? AND status = 'ACTIVE'",
                connected.couple().id()))
        .isEqualTo(2);
    assertThat(
            count(
                "SELECT count(*) FROM couple_pairing_slots WHERE invite_id = ?", invite.inviteId()))
        .isZero();
    assertError(() -> service.join(bob, invite.code()), "INVALID_INVITE");
  }

  @Test
  void rotationRevocationAndRejectionReleaseBothSlotsAndNeverReplayTheSecret() {
    UUID key = UUID.randomUUID();
    CreatedInvite first = service.create(alice, key);
    service.join(bob, first.code());
    CreatedInvite second = service.create(alice, UUID.randomUUID());
    assertThat(service.me(bob).pendingInvite()).isNull();
    assertThat(service.create(alice, key).code()).isNull();
    assertThat(service.me(alice).pendingInvite().id()).isEqualTo(second.inviteId());
    assertError(() -> service.join(bob, first.code()), "INVALID_INVITE");
    service.join(bob, second.code());
    service.reject(bob, second.inviteId());
    service.reject(bob, second.inviteId());
    assertThat(service.me(alice).pendingInvite()).isNull();
    assertThat(service.me(bob).pendingInvite()).isNull();
    CreatedInvite third = service.create(alice, UUID.randomUUID());
    service.revoke(alice, third.inviteId());
    service.revoke(alice, third.inviteId());
    assertError(() -> service.preview(bob, third.code()), "INVALID_INVITE");
  }

  @Test
  void deadlineIsStrictAndRejectionStillCommitsExpiryCleanup() {
    CreatedInvite invitation = service.create(alice, UUID.randomUUID());
    service.join(bob, invitation.code());
    jdbc.update(
        "UPDATE couple_invites SET created_at = now() - interval '25 hours', expires_at = now() - interval '1 hour' WHERE id = ?",
        invitation.inviteId());
    assertError(() -> service.confirm(bob, invitation.inviteId()), "INVITE_EXPIRED");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM couple_invites WHERE id = ?",
                String.class,
                invitation.inviteId()))
        .isEqualTo("EXPIRED");
    assertThat(
            count(
                "SELECT count(*) FROM couple_pairing_slots WHERE invite_id = ?",
                invitation.inviteId()))
        .isZero();
    service.expireInvites();
    service.expireInvites();
    assertThat(service.me(alice).pendingInvite()).isNull();
    assertThat(service.me(bob).pendingInvite()).isNull();
    assertError(() -> service.preview(bob, invitation.code()), "INVALID_INVITE");
    assertThat(service.create(bob, UUID.randomUUID()).code()).isNotBlank();
  }

  @Test
  void blocksImmediatelyHideProfilesAndOldRetriesNeverAffectLaterRelationships() {
    CreatedInvite original = connect(alice, bob);
    UUID blockKey = UUID.randomUUID();
    assertThat(service.end(alice, blockKey, true).status()).isEqualTo("COMPLETED");
    assertThat(service.me(alice).couple()).isNull();
    assertThat(service.me(bob).couple()).isNull();
    assertThat(service.confirm(bob, original.inviteId()).couple()).isNull();
    assertThat(
            count(
                "SELECT count(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                alice,
                bob))
        .isEqualTo(1);
    CreatedInvite blockedCode = service.create(bob, UUID.randomUUID());
    assertError(() -> service.preview(alice, blockedCode.code()), "INVALID_INVITE");
    assertError(() -> service.join(alice, blockedCode.code()), "INVALID_INVITE");
    CreatedInvite newCode = service.create(alice, UUID.randomUUID());
    assertError(() -> service.preview(bob, newCode.code()), "INVALID_INVITE");
    service.join(carol, newCode.code());
    service.confirm(alice, newCode.inviteId());
    UUID newCouple = service.confirm(carol, newCode.inviteId()).couple().id();
    service.end(alice, blockKey, true);
    assertThat(service.me(alice).couple().id()).isEqualTo(newCouple);
    assertThat(
            count(
                "SELECT count(*) FROM user_blocks WHERE blocker_id = ? AND blocked_id = ?",
                alice,
                carol))
        .isZero();
    assertError(() -> service.end(alice, blockKey, false), "IDEMPOTENCY_KEY_REUSED");
    assertThat(
            count(
                "SELECT count(*) FROM relationship_end_jobs WHERE initiated_by = ? AND status = 'COMPLETED' AND target_resource_count = 0",
                alice))
        .isEqualTo(1);
  }

  @Test
  void concurrentConfirmationsCreateExactlyOneCoupleAndTwoMemberships() throws Exception {
    CreatedInvite invite = service.create(alice, UUID.randomUUID());
    service.join(bob, invite.code());
    race(
        () -> service.confirm(alice, invite.inviteId()),
        () -> service.confirm(bob, invite.inviteId()));
    UUID coupleId = service.me(alice).couple().id();
    assertThat(service.me(bob).couple().id()).isEqualTo(coupleId);
    assertThat(count("SELECT count(*) FROM couples WHERE source_invite_id = ?", invite.inviteId()))
        .isEqualTo(1);
    assertThat(count("SELECT count(*) FROM couple_members WHERE couple_id = ?", coupleId))
        .isEqualTo(2);
  }

  @Test
  void concurrentClaimsReserveExactlyOneInvitee() throws Exception {
    CreatedInvite invite = service.create(alice, UUID.randomUUID());
    List<Object> outcomes =
        race(() -> service.join(bob, invite.code()), () -> service.join(carol, invite.code()));
    assertThat(outcomes.stream().filter(ApiException.class::isInstance)).hasSize(1);
    assertThat(
            count(
                "SELECT count(*) FROM couple_pairing_slots WHERE invite_id = ?", invite.inviteId()))
        .isEqualTo(2);
    assertThat(count("SELECT count(*) FROM couples WHERE source_invite_id = ?", invite.inviteId()))
        .isZero();
  }

  @Test
  void oneSlotPerUserPreventsOverlappingInviterAndInviteeRoles() {
    CreatedInvite aliceCode = service.create(alice, UUID.randomUUID());
    CreatedInvite bobCode = service.create(bob, UUID.randomUUID());
    assertError(() -> service.join(bob, aliceCode.code()), "PAIRING_CONFLICT");
    assertError(() -> service.preview(alice, aliceCode.code()), "INVALID_INVITE");
    service.join(carol, aliceCode.code());
    assertError(() -> service.create(carol, UUID.randomUUID()), "PAIRING_CONFLICT");
    assertError(() -> service.join(dana, aliceCode.code()), "INVALID_INVITE");
    assertError(() -> service.revoke(carol, aliceCode.inviteId()), "INVITE_NOT_FOUND");
    assertError(() -> service.confirm(dana, bobCode.inviteId()), "INVITE_NOT_FOUND");
  }

  @Test
  void failedCodeAttemptsPersistAcrossServiceInstancesAndResetAfterFifteenMinutes() {
    CreatedInvite invitation = service.create(alice, UUID.randomUUID());
    for (int index = 0; index < 10; index++) {
      if (index % 2 == 0) assertError(() -> service.preview(bob, "invalid"), "INVALID_INVITE");
      else assertError(() -> service.join(bob, "invalid"), "INVALID_INVITE");
    }
    CoupleService otherInstance = new CoupleService(jdbc, transactionManager, codec);
    assertError(() -> otherInstance.join(bob, invitation.code()), "INVITE_RATE_LIMITED");
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM couple_code_attempts WHERE user_id = ?", Integer.class, bob))
        .isEqualTo(11);
    jdbc.update(
        "UPDATE couple_code_attempts SET window_started_at = now() - interval '16 minutes' WHERE user_id = ?",
        bob);
    assertThat(otherInstance.preview(bob, invitation.code()).inviter().id()).isEqualTo(alice);
    assertThat(
            jdbc.queryForObject(
                "SELECT attempts FROM couple_code_attempts WHERE user_id = ?", Integer.class, bob))
        .isEqualTo(1);
  }

  @Test
  void inactiveAccountsAndUnfinishedRelationshipCleanupCannotPair() {
    jdbc.update("UPDATE users SET status = 'PENDING_ELIGIBILITY' WHERE id = ?", dana);
    assertError(() -> service.create(dana, UUID.randomUUID()), "ACCOUNT_NOT_ACTIVE");
    assertError(() -> service.me(null), "UNAUTHENTICATED");
    CreatedInvite original = connect(alice, bob);
    service.end(alice, UUID.randomUUID(), false);
    jdbc.update(
        """
        UPDATE relationship_end_jobs SET status = 'PROCESSING', target_resource_count = 1,
          processed_resource_count = 0, completed_at = NULL WHERE couple_id =
          (SELECT id FROM couples WHERE source_invite_id = ?)
        """,
        original.inviteId());
    assertError(() -> service.create(alice, UUID.randomUUID()), "PAIRING_CONFLICT");
    assertError(() -> service.create(bob, UUID.randomUUID()), "PAIRING_CONFLICT");
    CreatedInvite other = service.create(carol, UUID.randomUUID());
    assertError(() -> service.join(bob, other.code()), "PAIRING_CONFLICT");
  }

  @Test
  void endingIsUnilateralAndIdempotentEvenAfterReconnection() {
    connect(alice, bob);
    UUID key = UUID.randomUUID();
    service.end(bob, key, false);
    service.end(bob, key, false);
    assertThat(service.me(alice).couple()).isNull();
    assertThat(service.me(bob).couple()).isNull();
    connect(bob, carol);
    UUID current = service.me(bob).couple().id();
    service.end(bob, key, false);
    assertThat(service.me(bob).couple().id()).isEqualTo(current);
    assertError(() -> service.end(dana, UUID.randomUUID(), true), "PAIRING_CONFLICT");
  }

  private CreatedInvite connect(UUID inviter, UUID invitee) {
    CreatedInvite invite = service.create(inviter, UUID.randomUUID());
    service.join(invitee, invite.code());
    service.confirm(inviter, invite.inviteId());
    service.confirm(invitee, invite.inviteId());
    return invite;
  }

  private UUID account(String nickname) {
    UUID id = UUID.randomUUID();
    jdbc.update("INSERT INTO users (id, nickname, status) VALUES (?, ?, 'ACTIVE')", id, nickname);
    return id;
  }

  private int count(String sql, Object... arguments) {
    return jdbc.queryForObject(sql, Integer.class, arguments);
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
      var left = executor.submit(() -> concurrentResult(first, ready, start));
      var right = executor.submit(() -> concurrentResult(second, ready, start));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
    }
  }

  private static Object concurrentResult(
      Callable<?> call, CountDownLatch ready, CountDownLatch start) throws Exception {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS))
      throw new IllegalStateException("Concurrent test did not start");
    try {
      return call.call();
    } catch (ApiException exception) {
      return exception;
    }
  }
}
