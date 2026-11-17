package com.betchu.backend.notifications;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.betchu.backend.PostgresIntegrationTestSupport;
import com.betchu.backend.auth.AuthSecrets;
import com.betchu.backend.auth.AuthenticatedUser;
import com.betchu.backend.common.ApiException;
import com.betchu.backend.couples.CoupleService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@TestPropertySource(
    properties = {
      "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
      "betchu.notifications.enabled=true"
    })
class NotificationIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired NotificationService notices;
  @Autowired PushDispatcher dispatcher;
  @Autowired CoupleService couples;
  @Autowired JdbcTemplate jdbc;
  @Autowired com.betchu.backend.tutorial.TutorialService tutorials;
  @Autowired com.betchu.backend.game.MonsterService monsters;
  @MockitoBean ExpoPushClient expo;
  UUID alice, bob, session;
  String token;

  @BeforeEach
  void fixtures() {
    alice = account();
    bob = account();
    session = UUID.randomUUID();
    token = "ExpoPushToken[" + UUID.randomUUID() + "]";
    jdbc.update(
        "INSERT INTO auth_sessions(id,user_id,refresh_token_hash,status,expires_at) VALUES (?,?,?,'ACTIVE',clock_timestamp()+interval '1 day')",
        session,
        alice,
        AuthSecrets.hash(session.toString()));
    notices.register(new AuthenticatedUser(alice, session), token, "IOS");
  }

  @Test
  void pairingInboxIsPrivateAndDeduplicatesWithoutPushPermission() {
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    assertThat(notices.inbox(alice).notifications()).hasSize(1);
    assertThat(notices.inbox(alice).unreadCount()).isEqualTo(1);
    assertThat(notices.inbox(bob).notifications()).hasSize(1);
    UUID id = notices.inbox(alice).notifications().getFirst().id();
    notices.read(bob, List.of(id));
    assertThat(notices.inbox(alice).unreadCount()).isEqualTo(1);
    notices.read(alice, List.of(id));
    assertThat(notices.inbox(alice).unreadCount()).isZero();
    couples.reject(bob, invite.inviteId());
    assertThat(notices.inbox(alice).notifications()).isEmpty();
  }

  @Test
  void pushTracksAcceptanceAndProviderReceiptSeparately() {
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    notices.collect();
    when(expo.send(eq(token), any()))
        .thenReturn(new ExpoPushClient.Outcome("ACCEPTED", "test-ticket"));
    dispatcher.dispatch();
    assertThat(status()).isEqualTo("ACCEPTED");
    verify(expo).send(eq(token), any());
    jdbc.update(
        "UPDATE push_deliveries SET next_attempt_at=clock_timestamp() WHERE device_id IN (SELECT id FROM push_devices WHERE user_id=?)",
        alice);
    when(expo.receipt("test-ticket"))
        .thenReturn(new ExpoPushClient.Outcome("PROVIDER_ACCEPTED", null));
    dispatcher.dispatch();
    assertThat(status()).isEqualTo("PROVIDER_ACCEPTED");
  }

  @Test
  void closedRelationshipOrSessionCannotSendQueuedNotification() {
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    notices.collect();
    couples.confirm(alice, invite.inviteId());
    couples.confirm(bob, invite.inviteId());
    dispatcher.dispatch();
    verifyNoInteractions(expo);
    assertThat(status()).isEqualTo("FAILED");
  }

  @Test
  void unregisteredDeviceStopsFurtherDeliveryAndTransientFailureBacksOff() {
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    notices.collect();
    when(expo.send(eq(token), any())).thenReturn(new ExpoPushClient.Outcome("RETRY", null));
    dispatcher.dispatch();
    assertThat(status()).isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM push_deliveries p JOIN push_devices d ON d.id=p.device_id WHERE d.user_id=? AND p.next_attempt_at>clock_timestamp()",
                Integer.class,
                alice))
        .isEqualTo(1);
    jdbc.update(
        "UPDATE push_deliveries SET next_attempt_at=clock_timestamp() WHERE device_id IN (SELECT id FROM push_devices WHERE user_id=?)",
        alice);
    when(expo.send(eq(token), any())).thenReturn(new ExpoPushClient.Outcome("DEVICE_GONE", null));
    dispatcher.dispatch();
    assertThat(notices.preferences(new AuthenticatedUser(alice, session)).pushEnabled()).isFalse();
  }

  @Test
  void invalidTokensAreRejectedAndUnregisterIsIdempotent() {
    assertThatThrownBy(
            () -> notices.register(new AuthenticatedUser(alice, session), "not-a-token", "IOS"))
        .isInstanceOf(ApiException.class);
    assertThat(notices.unregister(new AuthenticatedUser(alice, session)).pushEnabled()).isFalse();
    assertThat(notices.unregister(new AuthenticatedUser(alice, session)).registeredDevices())
        .isZero();
  }

  @Test
  void anAlreadyRevokedSessionCannotRegisterAgainAfterAuthenticationRacesWithLogout() {
    jdbc.update("UPDATE auth_sessions SET status='REVOKED' WHERE id=?", session);
    assertThatThrownBy(() -> notices.register(new AuthenticatedUser(alice, session), token, "IOS"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void pendingInvitationReminderIsDeduplicatedAndUnreadItemsRemainReachable() {
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    jdbc.update(
        "UPDATE couple_invites SET expires_at=clock_timestamp()+interval '10 minutes' WHERE id=?",
        invite.inviteId());
    var first = notices.inbox(alice);
    assertThat(first.notifications())
        .extracting(NotificationService.Notice::kind)
        .containsExactlyInAnyOrder("INVITE", "INVITE_DEADLINE");
    notices.collect();
    assertThat(notices.inbox(alice).notifications()).hasSize(2);
    for (int i = 0; i < 101; i++)
      jdbc.update(
          "INSERT INTO notification_events(user_id,source_key,kind,route,expires_at) VALUES (?,?,'TEST','/',clock_timestamp()+interval '1 hour')",
          alice,
          "test:" + UUID.randomUUID());
    var newest = notices.inbox(alice);
    assertThat(newest.notifications()).hasSize(100);
    var next =
        notices.read(
            alice, newest.notifications().stream().map(NotificationService.Notice::id).toList());
    assertThat(next.unreadCount()).isEqualTo(3);
    assertThat(next.notifications().subList(0, 3)).allSatisfy(n -> assertThat(n.read()).isFalse());
  }

  private String status() {
    return jdbc.queryForObject(
        "SELECT p.status FROM push_deliveries p JOIN push_devices d ON d.id=p.device_id WHERE d.user_id=?",
        String.class,
        alice);
  }

  @Test
  void tutorialEventsSurviveRapidTransitionsAndPendingResultOnlyNotifiesItsSelector() {
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    couples.confirm(alice, invite.inviteId());
    couples.confirm(bob, invite.inviteId());
    for (UUID user : List.of(alice, bob))
      monsters.create(
          user,
          UUID.randomUUID(),
          new com.betchu.backend.starter.StarterModels.PreviewRequest(
              com.betchu.backend.starter.StarterModels.Species.STARLIGHT, "Fixture"));
    var now = java.time.Instant.now();
    var sent =
        tutorials.create(
            alice,
            UUID.randomUUID(),
            new com.betchu.backend.tutorial.TutorialModels.CreateInput(
                now.plusSeconds(3600), now.plusSeconds(3660)));
    tutorials.approve(
        bob,
        sent.id(),
        UUID.randomUUID(),
        new com.betchu.backend.tutorial.TutorialModels.ApproveInput(
            sent.questVersionId(),
            sent.rowVersion(),
            com.betchu.backend.tutorial.TutorialModels.Result.SUCCESS));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM quest_outbox WHERE quest_id=?", Integer.class, sent.id()))
        .isEqualTo(2);
    notices.collect();
    assertThat(
            notices.inbox(alice).notifications().stream().filter(n -> n.kind().equals("TUTORIAL")))
        .hasSize(2);
    jdbc.update(
        "WITH instant AS (SELECT clock_timestamp() AS value) UPDATE quest_versions SET due_at=instant.value-interval '2 minutes',result_at=instant.value-interval '1 minute',approval_deadline_at=instant.value-interval '2 minutes',result_confirmation_deadline_at=instant.value+interval '23 hours 59 minutes' FROM instant WHERE quest_id=?",
        sent.id());
    var open = tutorials.get(bob, sent.id());
    var selected =
        tutorials.selectResult(
            bob,
            sent.id(),
            UUID.randomUUID(),
            new com.betchu.backend.tutorial.TutorialModels.ResultInput(
                open.rowVersion(), com.betchu.backend.tutorial.TutorialModels.Result.SUCCESS));
    UUID event =
        jdbc.queryForObject(
            "SELECT id FROM quest_outbox WHERE quest_id=? AND row_version=?",
            UUID.class,
            sent.id(),
            selected.rowVersion());
    assertThat(
            jdbc.queryForObject(
                "SELECT recipient_id FROM quest_outbox WHERE id=?", UUID.class, event))
        .isEqualTo(bob);
    notices.collect();
    assertThat(
            jdbc.queryForList(
                "SELECT user_id FROM notification_events WHERE source_key=?",
                UUID.class,
                "quest-event:" + event))
        .containsExactly(bob);
    couples.end(alice, UUID.randomUUID(), false);
    assertThat(notices.inbox(bob).notifications()).isEmpty();
  }

  private UUID account() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,age_eligible_confirmed_at) VALUES (?,'Fixture','ACTIVE',clock_timestamp())",
        id);
    jdbc.update("INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,1000,0)", id);
    return id;
  }
}
