/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.container_orchestrator.orchestrator;

import io.airbyte.commons.json.Jsons;
import io.airbyte.workers.process.KubePodProcess;
import io.airbyte.workers.sync.OrchestratorConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The job orchestrator helps abstract over container launcher application differences across
 * replication, normalization, and custom dbt operators.
 *
 * @param <INPUT> job input type
 */
public interface JobOrchestrator<INPUT> {

  // used for logging
  String getOrchestratorName();

  // used to serialize the loaded input
  Class<INPUT> getInputClass();

  default Path getConfigDir() {
    return Path.of(KubePodProcess.CONFIG_DIR);
  }

  /**
   * Reads input from a file that was copied to the container launcher.
   *
   * @return input
   * @throws IOException when communicating with container
   */
  default INPUT readInput() throws IOException {
    return Jsons.deserialize(
        getConfigDir().resolve(OrchestratorConstants.INIT_FILE_INPUT).toFile(),
        getInputClass());
  }

  /**
   * Contains the unique logic that belongs to each type of job.
   *
   * @return an optional output value to place within the output document store item.
   */
  Optional<String> runJob() throws Exception;

  static <T> T readAndDeserializeFile(final Path path, final Class<T> type) throws IOException {
    return Jsons.deserialize(Files.readString(path), type);
  }

}
