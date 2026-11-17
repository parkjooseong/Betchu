package com.betchu.backend.safety;

import static com.betchu.backend.safety.SafetyModels.*;
import static org.assertj.core.api.Assertions.*;

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
import org.springframework.transaction.annotation.Transactional;

@Transactional
@TestPropertySource(
    properties = "betchu.couples.invite-hmac-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class AccountSafetyIntegrationTest extends PostgresIntegrationTestSupport {
  @Autowired AccountSafetyService safety;
  @Autowired CoupleService couples;
  @Autowired JdbcTemplate jdbc;
  UUID alice, bob, session;

  @BeforeEach
  void setup() {
    alice = account();
    bob = account();
    session = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO auth_sessions(id,user_id,refresh_token_hash,status,expires_at) VALUES (?,?,?,'ACTIVE',clock_timestamp()+interval '1 day')",
        session,
        alice,
        AuthSecrets.hash(session.toString()));
    var invite = couples.create(alice, UUID.randomUUID());
    couples.join(bob, invite.code());
    couples.confirm(alice, invite.inviteId());
    couples.confirm(bob, invite.inviteId());
  }

  @Test
  void reportIsPrivateBoundToKnownPartnerAndIdempotent() {
    UUID key = UUID.randomUUID();
    var input = new ReportInput(bob, "PRIVACY", "Private report content");
    var report = safety.report(alice, key, input);
    assertThat(safety.report(alice, key, input)).isEqualTo(report);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM user_reports WHERE reporter_id=?", Integer.class, alice))
        .isEqualTo(1);
    assertThatThrownBy(
            () ->
                safety.report(
                    alice,
                    UUID.randomUUID(),
                    new ReportInput(UUID.randomUUID(), "OTHER", "Unknown")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> safety.report(alice, key, new ReportInput(bob, "OTHER", "Changed")))
        .isInstanceOf(ApiException.class);
    assertThat(safety.export(bob, "ACCOUNT", null).records().toString())
        .doesNotContain("Private report content");
  }

  @Test
  void exportPagesNeverContainPartnerIdentifiersOrOtherWallets() {
    jdbc.update(
        "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'TEST',999,0)",
        UUID.randomUUID(),
        bob);
    for (int i = 0; i < 105; i++)
      jdbc.update(
          "INSERT INTO wallet_ledger(id,user_id,entry_type,available_delta,locked_delta) VALUES (?,?,'TEST',1,0)",
          UUID.randomUUID(),
          alice);
    var first = safety.export(alice, "LEDGER", null);
    assertThat(first.records()).hasSize(100);
    assertThat(first.nextCursor()).isNotNull();
    var second = safety.export(alice, "LEDGER", first.nextCursor());
    assertThat(second.records()).hasSize(5);
    assertThat(second.nextCursor()).isNull();
    assertThat(first.records().toString()).doesNotContain(bob.toString());
    assertThat(first.records())
        .allSatisfy(
            row -> assertThat(((Number) row.get("available_delta")).longValue()).isEqualTo(1));
    assertThat(safety.export(alice, "ACCOUNT", null).records()).hasSize(1);
    for (String section :
        List.of(
            "CONSENTS",
            "INVENTORY",
            "DRAWS",
            "MONSTERS",
            "PROGRESSION",
            "EQUIPMENT",
            "RESCUE",
            "REPORTS",
            "QUESTS")) assertThat(safety.export(alice, section, null).records()).isEmpty();
    assertThatThrownBy(() -> safety.export(alice, "auth_sessions", null))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void reportingTargetsDoNotExposeUnrelatedOrFormerPartnerProfiles() {
    UUID unrelated = account();
    assertThat(safety.reportTargets(alice))
        .extracting(AccountSafetyService.ReportTarget::id)
        .containsExactly(bob)
        .doesNotContain(unrelated);
    couples.end(alice, UUID.randomUUID(), false);
    jdbc.update("UPDATE users SET nickname='Changed private nickname' WHERE id=?", bob);
    var targets = safety.reportTargets(alice);
    assertThat(targets).hasSize(1);
    assertThat(targets.getFirst().nickname())
        .startsWith("이전 파트너 (")
        .doesNotContain("Changed private nickname");
    assertThat(
            safety
                .report(
                    alice, UUID.randomUUID(), new ReportInput(bob, "OTHER", "After disconnection"))
                .status())
        .isEqualTo("RECEIVED");
  }

  @Test
  void oldOrForeignSessionCannotDelete() {
    jdbc.update(
        "UPDATE auth_sessions SET created_at=clock_timestamp()-interval '11 minutes' WHERE id=?",
        session);
    assertThatThrownBy(
            () ->
                safety.delete(
                    new AuthenticatedUser(alice, session),
                    UUID.randomUUID(),
                    new DeleteInput("DELETE_MY_ACCOUNT")))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("다시 로그인");
    assertThatThrownBy(
            () ->
                safety.delete(
                    new AuthenticatedUser(bob, session),
                    UUID.randomUUID(),
                    new DeleteInput("DELETE_MY_ACCOUNT")))
        .isInstanceOf(ApiException.class);
    assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, alice))
        .isEqualTo("ACTIVE");
    assertThat(couples.me(bob).couple()).isNotNull();
  }

  @Test
  void deletionRevokesAccessEndsRelationshipAndScrubsIdentity() {
    String subject = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO oauth_identities(user_id,provider,provider_subject) VALUES (?,'GOOGLE',?)",
        alice,
        subject);
    var result =
        safety.delete(
            new AuthenticatedUser(alice, session),
            UUID.randomUUID(),
            new DeleteInput("DELETE_MY_ACCOUNT"));
    assertThat(result.status()).isEqualTo("PROCESSING");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM auth_sessions WHERE id=?", String.class, session))
        .isEqualTo("REVOKED");
    assertThat(couples.me(bob).couple()).isNull();
    assertThatThrownBy(() -> safety.export(alice, "ACCOUNT", null))
        .isInstanceOf(ApiException.class);
    safety.maintain();
    assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, alice))
        .isEqualTo("DELETED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM oauth_identities WHERE user_id=?", Integer.class, alice))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM deleted_identity_cooldowns WHERE identity_hash=?",
                Integer.class,
                AuthSecrets.hash("GOOGLE:" + subject)))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=?", String.class, alice))
        .isEqualTo("탈퇴한 사용자");
  }

  private UUID account() {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users(id,nickname,status,age_eligible_confirmed_at) VALUES (?,'Fixture','ACTIVE',now())",
        id);
    jdbc.update("INSERT INTO wallets(user_id,available_coins,locked_coins) VALUES (?,1000,0)", id);
    return id;
  }
}
