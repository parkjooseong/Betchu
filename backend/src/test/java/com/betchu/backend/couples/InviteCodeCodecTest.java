package com.betchu.backend.couples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.betchu.backend.common.ApiException;
import java.util.Base64;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class InviteCodeCodecTest {
  private final String secret = Base64.getEncoder().encodeToString(new byte[32]);
  private final InviteCodeCodec codec = new InviteCodeCodec(secret);

  @Test
  void generatesUnpredictableHumanReadableCodesAndOnlyHashesNormalizedValues() {
    var unique = new HashSet<String>();
    for (int index = 0; index < 100; index++) {
      String code = codec.generate();
      assertThat(code).matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){3}");
      assertThat(codec.hash(code)).hasSize(64).doesNotContain(code);
      assertThat(codec.hash("  " + code.toLowerCase() + "  "))
          .isEqualTo(codec.hash(code.replace("-", "")));
      unique.add(code);
    }
    assertThat(unique).hasSize(100);
  }

  @Test
  void hashesAreBoundToTheServerSecret() {
    String code = codec.generate();
    byte[] otherKey = new byte[32];
    otherKey[0] = 1;
    assertThat(codec.hash(code))
        .isNotEqualTo(new InviteCodeCodec(Base64.getEncoder().encodeToString(otherKey)).hash(code));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {"PRIVATE_INPUT", "AAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAA0", "AAAA AAAA AAAA AAAA"})
  void rejectsInvalidCodesWithoutExposingTheirContent(String code) {
    assertThatThrownBy(() -> codec.hash(code))
        .isInstanceOf(ApiException.class)
        .hasMessageNotContaining("PRIVATE_INPUT");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "not-base64", "c2hvcnQ="})
  void refusesMissingOrWeakHmacSecrets(String secret) {
    assertThatThrownBy(() -> new InviteCodeCodec(secret).generate())
        .isInstanceOf(ApiException.class)
        .satisfies(
            error ->
                assertThat(((ApiException) error).errorCode())
                    .isEqualTo("COUPLE_INVITES_UNAVAILABLE"));
  }

  @Test
  void acceptsOnlyOneStringCodeFieldInJson() {
    JsonMapper mapper = JsonMapper.builder().build();
    assertThat(
            mapper
                .readValue("{\"code\":\"AAAA-BBBB-CCCC-DDDD\"}", CoupleModels.CodeRequest.class)
                .code())
        .isEqualTo("AAAA-BBBB-CCCC-DDDD");
    for (String input :
        new String[] {
          "{}", "[]", "{\"code\":123}", "{\"code\":null}", "{\"code\":\"a\",\"userId\":\"b\"}"
        }) {
      assertThatThrownBy(() -> mapper.readValue(input, CoupleModels.CodeRequest.class))
          .isInstanceOf(RuntimeException.class);
    }
  }
}
