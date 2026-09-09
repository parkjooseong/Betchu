package com.betchu.backend.quests;

import static org.assertj.core.api.Assertions.*;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.quests.QuestModels.Category;
import com.betchu.backend.quests.QuestModels.DraftInput;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class QuestValidationTest {
  private static final Instant DUE = Instant.parse("2026-09-07T01:00:00Z");

  @Test
  void normalizesUnicodeAndAllowsOnlyInternalLfInCriteria() {
    DraftInput result =
        QuestValidation.normalize(input("\u2000별 \uD83D\uDE00\u00A0", "\u2000한 줄\n두 줄\u00A0"));
    assertThat(result.title()).isEqualTo("별 😀");
    assertThat(result.successCriteria()).isEqualTo("한 줄\n두 줄");
    assertThat(QuestValidation.normalize(input("😀".repeat(80), "a".repeat(1000))).title())
        .hasSize(160);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "\u2000\u00A0",
        "a\nb",
        "a\rb",
        "a\tb",
        "a\u0000b",
        "a\u200Db",
        "a\u2028b",
        "a\u2029b",
        "\uD800"
      })
  void rejectsEmptyAndUnsafeTitles(String title) {
    assertThatThrownBy(() -> QuestValidation.normalize(input(title, "condition")))
        .isInstanceOf(ApiException.class);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"", "a\rb", "a\r\nb", "a\tb", "a\u0000b", "a\u200Db", "a\u2028b", "\uDFFF"})
  void rejectsUnsafeCriteria(String criteria) {
    assertThatThrownBy(() -> QuestValidation.normalize(input("title", criteria)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void enforcesCodePointLimitsAndFinancialBounds() {
    assertThatThrownBy(() -> QuestValidation.normalize(input("😀".repeat(81), "a")))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> QuestValidation.normalize(input("a", "a".repeat(1001))))
        .isInstanceOf(ApiException.class);
    for (int[] values :
        new int[][] {
          {0, 0, 0}, {5, 0, 0}, {1, 99, 0}, {4, 200, 0}, {1, 100, -1}, {1, 100, 10081}
        }) {
      assertThatThrownBy(
              () ->
                  QuestValidation.normalize(
                      new DraftInput(
                          "title",
                          Category.CUSTOM,
                          "condition",
                          values[0],
                          values[1],
                          DUE,
                          DUE.plusSeconds(1),
                          values[2],
                          "NONE")))
          .isInstanceOf(ApiException.class);
    }
    assertThat(
            QuestValidation.normalize(
                    new DraftInput(
                        "title",
                        Category.CUSTOM,
                        "condition",
                        4,
                        100,
                        DUE,
                        DUE.plusSeconds(1),
                        10080,
                        "NONE"))
                .stake())
        .isEqualTo(100);
  }

  @Test
  void allowsPastDraftsAndNormalizesDatabaseTimestampPrecision() {
    DraftInput old =
        new DraftInput(
            "title",
            Category.CUSTOM,
            "condition",
            1,
            0,
            Instant.parse("2000-01-01T00:00:00.000000999Z"),
            Instant.parse("2000-01-01T00:00:01.123456999Z"),
            0,
            "NONE");
    DraftInput result = QuestValidation.normalize(old);
    assertThat(result.dueAt()).isEqualTo(Instant.parse("2000-01-01T00:00:00Z"));
    assertThat(result.resultAt()).isEqualTo(Instant.parse("2000-01-01T00:00:01.123456Z"));
  }

  @Test
  void rejectsEqualReversedOutOfRangeAndSubMicrosecondResultDates() {
    for (Instant result :
        new Instant[] {
          DUE, DUE.minusSeconds(1), DUE.plusNanos(1), Instant.parse("2101-01-01T00:00:00Z")
        }) {
      assertThatThrownBy(
              () ->
                  QuestValidation.normalize(
                      new DraftInput("a", Category.CUSTOM, "a", 1, 0, DUE, result, 0, "NONE")))
          .isInstanceOf(ApiException.class);
    }
    assertThatThrownBy(
            () ->
                QuestValidation.normalize(
                    new DraftInput(
                        "a",
                        Category.CUSTOM,
                        "a",
                        1,
                        0,
                        Instant.parse("1999-12-31T23:59:59Z"),
                        DUE,
                        0,
                        "NONE")))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void jsonParsingRequiresExplicitOffsetAndStrictTypes() {
    JsonMapper mapper = JsonMapper.builder().build();
    String json =
        """
        {"title":"title","category":"CUSTOM","successCriteria":"condition","difficulty":1,"stake":0,
         "dueAt":"2026-09-07T10:00:00+09:00","resultAt":"2026-09-07T11:00:00+09:00","minimumDurationMinutes":0,"evidenceMethod":"NONE"}
        """;
    assertThat(mapper.readValue(json, DraftInput.class).dueAt()).isEqualTo(DUE);
    for (String invalid :
        new String[] {
          json.replace("10:00:00+09:00", "10:00:00"),
          json.replace("\"difficulty\":1", "\"difficulty\":\"1\""),
          json.replace("\"stake\":0", "\"stake\":0.0"),
          json.replace("\"title\":\"title\"", "\"title\":null"),
          json.replace(
              "\"evidenceMethod\":\"NONE\"", "\"evidenceMethod\":\"NONE\",\"creatorId\":\"other\"")
        }) {
      assertThatThrownBy(() -> mapper.readValue(invalid, DraftInput.class))
          .isInstanceOf(RuntimeException.class);
    }
  }

  private static DraftInput input(String title, String criteria) {
    return new DraftInput(
        title, Category.CUSTOM, criteria, 1, 0, DUE, DUE.plusSeconds(1), 0, "NONE");
  }
}
