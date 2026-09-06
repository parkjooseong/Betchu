package com.betchu.backend.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.betchu.backend.starter.StarterModels.GrowthMilestone;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import com.betchu.backend.starter.StarterModels.Species;
import com.betchu.backend.starter.StarterModels.Stage;
import com.betchu.backend.starter.StarterModels.Starter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StarterServiceTest {

  private final StarterService service = new StarterService();

  @Test
  void catalogueFollowsPlannedStarterAndGrowthRules() {
    var catalogue = service.catalogue();

    assertThat(catalogue.starters()).extracting(Starter::species).containsExactly(Species.values());
    assertThat(catalogue.starters())
        .extracting(Starter::displayName)
        .containsExactly("별빛", "파도", "노을", "숲");
    assertThat(catalogue.initialStats().level()).isEqualTo(1);
    assertThat(catalogue.initialStats().hp()).isEqualTo(100);
    assertThat(catalogue.initialStats().atk()).isEqualTo(10);
    assertThat(catalogue.initialStats().power()).isEqualTo(700);
    assertThat(catalogue.initialStats().exp()).isZero();
    assertThat(catalogue.initialStats().nextLevelRequiredExp()).isEqualTo(80);
    assertThat(catalogue.nameRules().minLength()).isEqualTo(1);
    assertThat(catalogue.nameRules().maxLength()).isEqualTo(10);
    assertThat(catalogue.growthMilestones())
        .extracting(GrowthMilestone::requiredSuccessCount)
        .containsExactly(1, 20, 40, 60, 80);
    assertThat(catalogue.growthMilestones().getFirst().description()).contains("튜토리얼");
    assertThat(catalogue.growthMilestones().getLast().description()).contains("P2");
  }

  @ParameterizedTest
  @EnumSource(Species.class)
  void everySpeciesStartsWithTheSameEggStatsWithoutRecognizedSuccess(Species species) {
    var result = service.preview(new PreviewRequest(species, "배츄"));

    assertThat(result.previewOnly()).isTrue();
    assertThat(result.starter().species()).isEqualTo(species);
    assertThat(result.stage()).isEqualTo(Stage.EGG);
    assertThat(result.recognizedSuccessCount()).isZero();
    assertThat(result.stats()).isEqualTo(service.catalogue().initialStats());
    assertThat(result.growthMilestones()).isEqualTo(service.catalogue().growthMilestones());
  }

  @Test
  void normalizesNfcAndTrimsUnicodeWhitespaceBeforeCountingCodePoints() {
    var result =
        service.preview(new PreviewRequest(Species.WAVE, "\u0085\u00a0 \u1107\u1162츄 \u3000"));

    assertThat(result.name()).isEqualTo("배츄");
    assertThat(service.preview(new PreviewRequest(Species.WAVE, "\u1100\u1161".repeat(10))).name())
        .isEqualTo("가".repeat(10));
  }

  @Test
  void acceptsTenSupplementaryCodePointsAndInteriorSpaces() {
    assertThat(service.preview(new PreviewRequest(Species.SUNSET, "🌟".repeat(10))).name())
        .isEqualTo("🌟".repeat(10));
    assertThat(service.preview(new PreviewRequest(Species.FOREST, "우리 배츄")).name())
        .isEqualTo("우리 배츄");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        " \u00a0\u0085\u3000 ",
        "12345678901",
        "🌟🌟🌟🌟🌟🌟🌟🌟🌟🌟🌟",
        "배\n츄",
        "배\r츄",
        "배\t츄",
        "배\u0000츄",
        "배\u007f츄",
        "배\u0085츄",
        "배\u200b츄",
        "배\u200d츄",
        "배\ufeff츄",
        "배\u2028츄",
        "배\u2029츄"
      })
  void rejectsInvalidNames(String name) {
    assertThatThrownBy(() -> service.preview(new PreviewRequest(Species.STARLIGHT, name)))
        .isInstanceOf(InvalidStarterSelectionException.class);
  }

  @Test
  void rejectsMissingSelection() {
    assertThatThrownBy(() -> service.preview(null))
        .isInstanceOf(InvalidStarterSelectionException.class);
    assertThatThrownBy(() -> service.preview(new PreviewRequest(null, "배츄")))
        .isInstanceOf(InvalidStarterSelectionException.class);
  }

  @Test
  void previewRequestsDoNotRetainPreviousSelection() {
    var request = new PreviewRequest(Species.STARLIGHT, "별이");
    var first = service.preview(request);

    service.preview(new PreviewRequest(Species.FOREST, "나무"));

    assertThat(service.preview(request)).isEqualTo(first);
    assertThat(service.catalogue().starters()).hasSize(4);
  }
}
