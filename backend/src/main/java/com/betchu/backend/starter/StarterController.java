package com.betchu.backend.starter;

import com.betchu.backend.starter.StarterModels.Catalogue;
import com.betchu.backend.starter.StarterModels.Preview;
import com.betchu.backend.starter.StarterModels.PreviewRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monsters")
public class StarterController {

  private final StarterService service;

  public StarterController(StarterService service) {
    this.service = service;
  }

  @GetMapping("/starters")
  public Catalogue catalogue() {
    return service.catalogue();
  }

  @PostMapping("/starter-preview")
  public Preview preview(@RequestBody PreviewRequest request) {
    return service.preview(request);
  }
}
