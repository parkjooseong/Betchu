package com.betchu.backend.quests;

import com.betchu.backend.common.ApiException;
import com.betchu.backend.quests.QuestModels.DraftInput;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

final class QuestValidation {
  private static final Pattern EDGE = Pattern.compile("^\\p{IsWhite_Space}+|\\p{IsWhite_Space}+$");
  private static final Pattern TITLE_CONTROL = Pattern.compile("[\\p{Cc}\\p{Cf}]|\\R");
  private static final Pattern CRITERIA_CONTROL =
      Pattern.compile("[\\p{Cc}&&[^\\n]]|\\p{Cf}|[\\u2028\\u2029]");
  private static final Instant MIN = Instant.parse("2000-01-01T00:00:00Z");
  private static final Instant MAX = Instant.parse("2101-01-01T00:00:00Z");

  private QuestValidation() {}

  static DraftInput normalize(DraftInput input) {
    if (input == null
        || input.category() == null
        || input.difficulty() < 1
        || input.difficulty() > 4
        || (input.stake() != 0 && input.stake() != 100)
        || input.minimumDurationMinutes() < 0
        || input.minimumDurationMinutes() > 10080
        || !"NONE".equals(input.evidenceMethod())
        || !inRange(input.dueAt())
        || !inRange(input.resultAt())
        || !input
            .resultAt()
            .truncatedTo(ChronoUnit.MICROS)
            .isAfter(input.dueAt().truncatedTo(ChronoUnit.MICROS))) throw invalid();
    return new DraftInput(
        text(input.title(), 80, TITLE_CONTROL),
        input.category(),
        text(input.successCriteria(), 1000, CRITERIA_CONTROL),
        input.difficulty(),
        input.stake(),
        input.dueAt().truncatedTo(ChronoUnit.MICROS),
        input.resultAt().truncatedTo(ChronoUnit.MICROS),
        input.minimumDurationMinutes(),
        input.evidenceMethod());
  }

  private static boolean inRange(Instant value) {
    return value != null && !value.isBefore(MIN) && value.isBefore(MAX);
  }

  static String message(String value) {
    return text(value, 1000, CRITERIA_CONTROL);
  }

  private static String text(String value, int maximum, Pattern forbidden) {
    if (value == null) throw invalid();
    String result = EDGE.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll("");
    int size = result.codePointCount(0, result.length());
    // PostgreSQL UTF-8 does not accept isolated UTF-16 surrogates or NUL.
    boolean unpaired = result.codePoints().anyMatch(point -> point >= 0xD800 && point <= 0xDFFF);
    if (size < 1 || size > maximum || forbidden.matcher(result).find() || unpaired) throw invalid();
    return result;
  }

  static ApiException invalid() {
    return new ApiException(
        HttpStatus.BAD_REQUEST, "INVALID_QUEST_REQUEST", "초안 입력과 요청 값을 확인해 주세요.");
  }
}
