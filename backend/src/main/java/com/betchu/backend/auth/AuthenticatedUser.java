package com.betchu.backend.auth;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UUID sessionId) {}
