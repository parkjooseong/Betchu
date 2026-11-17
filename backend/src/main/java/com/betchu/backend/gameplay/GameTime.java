package com.betchu.backend.gameplay;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class GameTime {
  private final JdbcTemplate jdbc;

  public GameTime(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Instant now() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }
}
