/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.support;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.airbyte.config.User;
import io.airbyte.config.persistence.UserPersistence;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.context.ServerRequestContext;
import io.micronaut.security.utils.SecurityService;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@MicronautTest
@Requires(env = {Environment.TEST})
@Property(name = "micronaut.security.enabled",
          value = "true")
public class SecurityAwareCurrentUserServiceTest {

  @MockBean(SecurityService.class)
  SecurityService mockSecurityService() {
    return Mockito.mock(SecurityService.class);
  }

  @MockBean(UserPersistence.class)
  UserPersistence mockUserPersistence() {
    return Mockito.mock(UserPersistence.class);
  }

  @Inject
  SecurityAwareCurrentUserService currentUserService;

  @Inject
  SecurityService securityService;

  @Inject
  UserPersistence userPersistence;

  @BeforeEach
  void setUp() {
    // set up a mock request context, details don't matter, just needed to make the
    // @RequestScope work on the SecurityAwareCurrentUserService
    ServerRequestContext.set(HttpRequest.GET("/"));
  }

  @Test
  void testgetCurrentUser() throws IOException {
    final String authUserId = "testUser";
    final User expectedUser = new User().withAuthUserId(authUserId);

    when(securityService.username()).thenReturn(Optional.of(authUserId));
    when(userPersistence.getUserByAuthId(authUserId)).thenReturn(Optional.of(expectedUser));

    // First call - should fetch from userPersistence
    final User user1 = currentUserService.getCurrentUser();
    Assertions.assertEquals(expectedUser, user1);

    // Second call - should use cached user
    final User user2 = currentUserService.getCurrentUser();
    Assertions.assertEquals(expectedUser, user2);

    // Verify that getUserByAuthId is called only once
    verify(userPersistence, times(1)).getUserByAuthId(authUserId);
  }

}
