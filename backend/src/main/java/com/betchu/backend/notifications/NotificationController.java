package com.betchu.backend.notifications;

import static com.betchu.backend.common.RequestFields.*;

import com.betchu.backend.auth.AuthenticatedUser;
import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
  public record DeviceInput(String token, String platform) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static DeviceInput parse(JsonNode input) {
      object(input, "token", "platform");
      return new DeviceInput(
          text(input, "token", 200), choice(input, "platform", "ANDROID", "IOS"));
    }
  }

  public record ReadInput(List<UUID> ids) {
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ReadInput parse(JsonNode input) {
      object(input, "ids");
      if (!input.path("ids").isArray() || input.path("ids").size() > 100) throw invalid();
      List<UUID> ids = new ArrayList<>();
      for (var id : input.path("ids")) {
        if (!id.isString()) throw invalid();
        ids.add(uuid(id.asString()));
      }
      return new ReadInput(ids);
    }
  }

  private final NotificationService service;

  public NotificationController(NotificationService service) {
    this.service = service;
  }

  @GetMapping
  public NotificationService.Inbox inbox(@AuthenticationPrincipal AuthenticatedUser user) {
    return service.inbox(user.userId());
  }

  @PostMapping("/read")
  public NotificationService.Inbox read(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody ReadInput input) {
    return service.read(user.userId(), input.ids());
  }

  @GetMapping("/device")
  public NotificationService.Preferences preferences(
      @AuthenticationPrincipal AuthenticatedUser user) {
    return service.preferences(user);
  }

  @PutMapping("/device")
  public NotificationService.Preferences register(
      @AuthenticationPrincipal AuthenticatedUser user, @RequestBody DeviceInput input) {
    return service.register(user, input.token(), input.platform());
  }

  @DeleteMapping("/device")
  public NotificationService.Preferences unregister(
      @AuthenticationPrincipal AuthenticatedUser user) {
    return service.unregister(user);
  }
}
