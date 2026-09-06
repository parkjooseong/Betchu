package com.betchu.backend.starter;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.betchu.backend.config.SecurityConfig;
import com.betchu.backend.system.SystemController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {StarterController.class, SystemController.class},
    properties = {
      "betchu.security.enabled=true",
      "betchu.cors.allowed-origins=http://localhost:8081, http://localhost:19006"
    })
@Import({StarterService.class, SecurityConfig.class})
class StarterControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private JwtDecoder jwtDecoder;

  @Test
  void catalogueIsPublicWithProductionSecurityEnabled() throws Exception {
    mvc.perform(get("/api/v1/monsters/starters"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(
            jsonPath("$.starters[*].species", contains("STARLIGHT", "WAVE", "SUNSET", "FOREST")))
        .andExpect(jsonPath("$.initialStats.level").value(1))
        .andExpect(jsonPath("$.initialStats.hp").value(100))
        .andExpect(jsonPath("$.initialStats.atk").value(10))
        .andExpect(jsonPath("$.initialStats.power").value(700))
        .andExpect(jsonPath("$.initialStats.exp").value(0))
        .andExpect(jsonPath("$.initialStats.nextLevelRequiredExp").value(80))
        .andExpect(jsonPath("$.nameRules.minLength").value(1))
        .andExpect(jsonPath("$.nameRules.maxLength").value(10))
        .andExpect(
            jsonPath(
                "$.growthMilestones[*].type",
                contains("HATCH", "INTERMEDIATE", "FINAL", "MASTERY", "SUCCESS_80")))
        .andExpect(
            jsonPath("$.growthMilestones[*].requiredSuccessCount", contains(1, 20, 40, 60, 80)));
  }

  @Test
  void previewIsPublicAndReturnsTheNormalizedSelectionWithoutCreatingAnAccount() throws Exception {
    mvc.perform(
            post("/api/v1/monsters/starter-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"species\":\"WAVE\",\"name\":\"\\u00a0\\u1107\\u1162츄\\u3000\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.previewOnly").value(true))
        .andExpect(jsonPath("$.starter.species").value("WAVE"))
        .andExpect(jsonPath("$.starter.displayName").value("파도"))
        .andExpect(jsonPath("$.name").value("배츄"))
        .andExpect(jsonPath("$.stage").value("EGG"))
        .andExpect(jsonPath("$.recognizedSuccessCount").value(0))
        .andExpect(jsonPath("$.stats.power").value(700))
        .andExpect(jsonPath("$.growthMilestones.length()").value(5))
        .andExpect(jsonPath("$.id").doesNotExist())
        .andExpect(jsonPath("$.userId").doesNotExist())
        .andExpect(jsonPath("$.coins").doesNotExist());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "null",
        "{}",
        "[]",
        "false",
        "{",
        "{\"species\":\"WAVE\"}",
        "{\"name\":\"PRIVATE_INPUT\"}",
        "{\"species\":null,\"name\":\"PRIVATE_INPUT\"}",
        "{\"species\":\"PRIVATE_INPUT\",\"name\":\"배츄\"}",
        "{\"species\":0,\"name\":\"PRIVATE_INPUT\"}",
        "{\"species\":\"wave\",\"name\":\"PRIVATE_INPUT\"}",
        "{\"species\":\"WAVE\",\"name\":null}",
        "{\"species\":\"WAVE\",\"name\":123}",
        "{\"species\":\"WAVE\",\"name\":false}",
        "{\"species\":\"WAVE\",\"name\":[]}",
        "{\"species\":\"WAVE\",\"name\":\"\"}",
        "{\"species\":\"WAVE\",\"name\":\" \\u00a0\\u3000 \"}",
        "{\"species\":\"WAVE\",\"name\":\"12345678901\"}",
        "{\"species\":\"WAVE\",\"name\":\"배\\n츄\"}",
        "{\"species\":\"WAVE\",\"name\":\"배\\u200b츄\"}",
        "{\"species\":\"WAVE\",\"name\":\"배\\u2028츄\"}",
        "{\"species\":\"WAVE\",\"name\":\"배츄\",\"userId\":\"PRIVATE_INPUT\"}"
      })
  void invalidSelectionReturnsGenericProblemDetails(String input) throws Exception {
    mvc.perform(
            post("/api/v1/monsters/starter-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(input))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.type").value("about:blank"))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.errorCode").value("INVALID_STARTER_SELECTION"))
        .andExpect(jsonPath("$.detail").isNotEmpty())
        .andExpect(content().string(not(containsString("PRIVATE_INPUT"))));
  }

  @Test
  void otherMonsterRoutesAndMethodsRemainProtected() throws Exception {
    mvc.perform(post("/api/v1/monsters/starter")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/monsters/me")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/monsters/starters")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/monsters/starter-preview")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/system/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"http://localhost:8081", "http://localhost:19006"})
  void allowsConfiguredWebOriginsForCatalogueAndPreviewPreflight(String origin) throws Exception {
    mvc.perform(get("/api/v1/monsters/starters").header("Origin", origin))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", origin));

    mvc.perform(
            options("/api/v1/monsters/starter-preview")
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", origin))
        .andExpect(header().string("Access-Control-Allow-Methods", "POST"))
        .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
  }

  @Test
  void rejectsUnconfiguredOriginsAndOtherCorsMethods() throws Exception {
    mvc.perform(
            options("/api/v1/monsters/starter-preview")
                .header("Origin", "https://unconfigured.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    mvc.perform(
            options("/api/v1/monsters/starters")
                .header("Origin", "http://localhost:8081")
                .header("Access-Control-Request-Method", "DELETE"))
        .andExpect(status().isForbidden());
  }
}
