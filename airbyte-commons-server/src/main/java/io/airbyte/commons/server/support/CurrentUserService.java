/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.commons.server.support;

import io.airbyte.config.User;

/**
 * Interface for retrieving the User associated with the current request.
 */
public interface CurrentUserService {

  User getCurrentUser();

}
