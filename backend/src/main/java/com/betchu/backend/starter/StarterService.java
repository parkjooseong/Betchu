package com.betchu.backend.starter;

import static com.betchu.backend.starter.StarterModels.MilestoneType.*;
import static com.betchu.backend.starter.StarterModels.Species.*;

import com.betchu.backend.starter.StarterModels.Catalogue;
import com.betchu.backend.starter.StarterModels.GrowthMilestone;
import com.betchu.backend.starter.StarterModels.NameRules;
import com.betchu.backend.starter.StarterModels.Preview;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import com.betchu.backend.starter.StarterModels.Stage;
import com.betchu.backend.starter.StarterModels.Starter;
import com.betchu.backend.starter.StarterModels.Stats;
import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class StarterService {

  private static final Pattern EDGE_WHITESPACE =
      Pattern.compile("^\\p{IsWhite_Space}+|\\p{IsWhite_Space}+$");
  private static final Pattern FORBIDDEN_NAME_CHARACTERS = Pattern.compile("[\\p{Cc}\\p{Cf}]|\\R");
  private static final int BASE_HP = 100;
  private static final int BASE_ATK = 10;
  private static final Stats INITIAL_STATS =
      new Stats(1, BASE_HP, BASE_ATK, BASE_HP * 5 + BASE_ATK * 20, 0, 80);
  private static final NameRules NAME_RULES = new NameRules(1, 10);
  private static final List<Starter> STARTERS =
      List.of(
          new Starter(STARLIGHT, "별빛", "노랑·보라, 밤하늘과 별빛 느낌"),
          new Starter(WAVE, "파도", "하양·파랑, 말랑한 파도와 바다 느낌"),
          new Starter(SUNSET, "노을", "빨강·갈색, 따뜻한 노을과 땅 느낌"),
          new Starter(FOREST, "숲", "초록·갈색, 포근한 나무와 숲 느낌"));
  private static final List<GrowthMilestone> GROWTH_MILESTONES =
      List.of(
          new GrowthMilestone(HATCH, 1, "튜토리얼 성공 또는 첫 인정 성공으로 알에서 아기 배츄로 부화해요."),
          new GrowthMilestone(INTERMEDIATE, 20, "인정 성공 20회에 중간 성장해요."),
          new GrowthMilestone(FINAL, 40, "인정 성공 40회에 최종 진화해요."),
          new GrowthMilestone(MASTERY, 60, "인정 성공 60회에 종별 오라·칭호·전투력 0 숙련 장신구를 받아요."),
          new GrowthMilestone(SUCCESS_80, 80, "인정 성공 80회 달성을 기록해요. 새 배츄 알 고르기는 추후 제공돼요."));

  public Catalogue catalogue() {
    return new Catalogue(STARTERS, INITIAL_STATS, NAME_RULES, GROWTH_MILESTONES);
  }

  public Preview preview(PreviewRequest request) {
    if (request == null || request.species() == null || request.name() == null) {
      throw new InvalidStarterSelectionException();
    }
    String name =
        EDGE_WHITESPACE
            .matcher(Normalizer.normalize(request.name(), Normalizer.Form.NFC))
            .replaceAll("");
    int length = name.codePointCount(0, name.length());
    if (length < NAME_RULES.minLength()
        || length > NAME_RULES.maxLength()
        || FORBIDDEN_NAME_CHARACTERS.matcher(name).find()) {
      throw new InvalidStarterSelectionException();
    }
    Starter starter =
        STARTERS.stream()
            .filter(candidate -> candidate.species() == request.species())
            .findFirst()
            .orElseThrow(InvalidStarterSelectionException::new);
    return new Preview(true, starter, name, Stage.EGG, 0, INITIAL_STATS, GROWTH_MILESTONES);
  }
}
