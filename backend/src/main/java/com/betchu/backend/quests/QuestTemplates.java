package com.betchu.backend.quests;

import com.betchu.backend.quests.QuestModels.*;
import java.time.Instant;
import java.util.List;

public final class QuestTemplates {
  private QuestTemplates() {}

  public record Template(
      String code,
      String title,
      Category category,
      String successCriteria,
      int difficulty,
      int minimumDurationMinutes,
      List<Integer> stakes) {}

  public static List<Template> catalogue() {
    return List.of(
        new Template(
            "READ_15-v1",
            "15분 독서하기",
            Category.STUDY,
            "스스로 고른 책을 15분 읽고 기억에 남는 내용을 파트너에게 이야기하기",
            1,
            15,
            List.of(0, 100)),
        new Template(
            "TIDY_30-v1",
            "30분 공간 정리하기",
            Category.HOUSEWORK,
            "스스로 고른 공간을 30분 동안 정리하고 완료한 내용을 파트너에게 이야기하기",
            2,
            30,
            List.of(0, 100)),
        new Template(
            "STUDY_60-v1",
            "1시간 집중 공부하기",
            Category.STUDY,
            "스스로 고른 주제를 휴식 시간을 제외하고 60분 공부한 뒤 배운 내용을 파트너에게 이야기하기",
            3,
            60,
            List.of(0, 100, 200)),
        new Template(
            "PROJECT_120-v1",
            "2시간 개인 프로젝트하기",
            Category.STUDY,
            "스스로 고른 개인 프로젝트에 휴식을 제외하고 총 120분 참여한 뒤 진행한 내용을 파트너에게 이야기하기",
            4,
            120,
            List.of(0, 100, 200, 300)));
  }

  static Template get(String code) {
    return catalogue().stream()
        .filter(t -> t.code().equals(code))
        .findFirst()
        .orElseThrow(QuestValidation::invalid);
  }

  static DraftInput input(Template template, int stake, Instant due, Instant result) {
    if (!template.stakes().contains(stake)) throw QuestValidation.invalid();
    return new DraftInput(
        template.title(),
        template.category(),
        template.successCriteria(),
        template.difficulty(),
        stake,
        due,
        result,
        template.minimumDurationMinutes(),
        "NONE");
  }

  static void validate(Template template, DraftInput input) {
    if (!input(template, input.stake(), input.dueAt(), input.resultAt()).equals(input))
      throw QuestValidation.invalid();
  }

  public static int reward(String source, int difficulty, int stake) {
    return "TEMPLATE".equals(source)
        ? stake * (difficulty == 1 ? 50 : difficulty == 2 ? 75 : 100) / 100
        : stake / 2;
  }

  public static boolean low(String source, int difficulty) {
    return !"TEMPLATE".equals(source) || difficulty <= 2;
  }
}
