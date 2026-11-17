package com.betchu.backend.quests;

import static com.betchu.backend.common.RequestFields.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/quest-templates")
public class QuestTemplateController {
  public record TemplateInput(int stake, Instant dueAt, Instant resultAt) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static TemplateInput parse(JsonNode input) {
      object(input, "stake", "dueAt", "resultAt");
      if (!input.path("stake").isIntegralNumber() || !input.path("stake").canConvertToInt())
        throw invalid();
      try {
        return new TemplateInput(
            input.path("stake").asInt(),
            OffsetDateTime.parse(text(input, "dueAt", 60)).toInstant(),
            OffsetDateTime.parse(text(input, "resultAt", 60)).toInstant());
      } catch (java.time.DateTimeException ex) {
        throw invalid();
      }
    }
  }

  private final QuestService quests;

  public QuestTemplateController(QuestService quests) {
    this.quests = quests;
  }

  @GetMapping
  public List<QuestTemplates.Template> catalogue() {
    return QuestTemplates.catalogue();
  }

  @PostMapping("/{code}/draft")
  public QuestModels.DraftView create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(name = "Idempotency-Key", required = false) String key,
      @PathVariable String code,
      @RequestBody TemplateInput input) {
    return quests.createTemplate(
        user.userId(), uuid(key), code, input.stake(), input.dueAt(), input.resultAt());
  }
}
