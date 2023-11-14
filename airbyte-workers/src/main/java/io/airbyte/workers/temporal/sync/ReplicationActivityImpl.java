/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import static io.airbyte.metrics.lib.ApmTraceConstants.ACTIVITY_TRACE_OPERATION_NAME;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.ATTEMPT_NUMBER_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.CONNECTION_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.DESTINATION_DOCKER_IMAGE_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.JOB_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.REPLICATION_BYTES_SYNCED_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.REPLICATION_RECORDS_SYNCED_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.REPLICATION_STATUS_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.SOURCE_DOCKER_IMAGE_KEY;

import datadog.trace.api.Trace;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.model.generated.StreamDescriptor;
import io.airbyte.commons.functional.CheckedSupplier;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.temporal.HeartbeatUtils;
import io.airbyte.commons.temporal.utils.PayloadChecker;
import io.airbyte.config.AirbyteConfigValidator;
import io.airbyte.config.ConfigSchema;
import io.airbyte.config.Configs.WorkerEnvironment;
import io.airbyte.config.ReplicationAttemptSummary;
import io.airbyte.config.ReplicationOutput;
import io.airbyte.config.StandardSyncInput;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.config.StandardSyncSummary;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.config.secrets.hydration.SecretsHydrator;
import io.airbyte.featureflag.Connection;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.Multi;
import io.airbyte.featureflag.RemoveLargeSyncInputs;
import io.airbyte.featureflag.UseWorkloadApi;
import io.airbyte.featureflag.Workspace;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.metrics.lib.MetricAttribute;
import io.airbyte.metrics.lib.MetricClient;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import io.airbyte.persistence.job.models.IntegrationLauncherConfig;
import io.airbyte.persistence.job.models.JobRunConfig;
import io.airbyte.persistence.job.models.ReplicationInput;
import io.airbyte.workers.ReplicationInputHydrator;
import io.airbyte.workers.Worker;
import io.airbyte.workers.helper.BackfillHelper;
import io.airbyte.workers.models.ReplicationActivityInput;
import io.airbyte.workers.orchestrator.OrchestratorHandleFactory;
import io.airbyte.workers.sync.WorkloadApiWorker;
import io.airbyte.workers.temporal.TemporalAttemptExecution;
import io.airbyte.workers.workload.WorkloadIdGenerator;
import io.airbyte.workload.api.client2.generated.WorkloadApi;
import io.micronaut.context.annotation.Value;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replication temporal activity impl.
 */
@Singleton
public class ReplicationActivityImpl implements ReplicationActivity {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationActivityImpl.class);
  private static final int MAX_TEMPORAL_MESSAGE_SIZE = 2 * 1024 * 1024;

  private final ReplicationInputHydrator replicationInputHydrator;
  private final SecretsHydrator secretsHydrator;
  private final Path workspaceRoot;
  private final WorkerEnvironment workerEnvironment;
  private final LogConfigs logConfigs;
  private final String airbyteVersion;
  private final AirbyteConfigValidator airbyteConfigValidator;
  private final AirbyteApiClient airbyteApiClient;
  private final WorkloadApi workloadApi;
  private final WorkloadIdGenerator workloadIdGenerator;
  private final OrchestratorHandleFactory orchestratorHandleFactory;
  private final MetricClient metricClient;
  private final FeatureFlagClient featureFlagClient;

  public ReplicationActivityImpl(final SecretsHydrator secretsHydrator,
                                 @Named("workspaceRoot") final Path workspaceRoot,
                                 final WorkerEnvironment workerEnvironment,
                                 final LogConfigs logConfigs,
                                 @Value("${airbyte.version}") final String airbyteVersion,
                                 final AirbyteConfigValidator airbyteConfigValidator,
                                 final AirbyteApiClient airbyteApiClient,
                                 final WorkloadApi workloadApi,
                                 final WorkloadIdGenerator workloadIdGenerator,
                                 final OrchestratorHandleFactory orchestratorHandleFactory,
                                 final MetricClient metricClient,
                                 final FeatureFlagClient featureFlagClient) {
    this.replicationInputHydrator = new ReplicationInputHydrator(airbyteApiClient.getConnectionApi(),
        airbyteApiClient.getJobsApi(),
        airbyteApiClient.getStateApi(),
        secretsHydrator,
        featureFlagClient);
    this.secretsHydrator = secretsHydrator;
    this.workspaceRoot = workspaceRoot;
    this.workerEnvironment = workerEnvironment;
    this.logConfigs = logConfigs;
    this.airbyteVersion = airbyteVersion;
    this.airbyteConfigValidator = airbyteConfigValidator;
    this.airbyteApiClient = airbyteApiClient;
    this.workloadApi = workloadApi;
    this.workloadIdGenerator = workloadIdGenerator;
    this.orchestratorHandleFactory = orchestratorHandleFactory;
    this.metricClient = metricClient;
    this.featureFlagClient = featureFlagClient;
  }

  /**
   * Performs the replication activity.
   * <p>
   * Takes a lite input (no catalog, no state, no secrets) to avoid passing those through Temporal and
   * hydrates it before launching the replication orchestrator.
   * <p>
   * TODO: this is the preferred method. Once we remove `replicate`, this can be renamed.
   *
   * @param replicationActivityInput the input to the replication activity
   * @return output from the replication activity, populated in the StandardSyncOutput
   */
  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public StandardSyncOutput replicateV2(final ReplicationActivityInput replicationActivityInput) {
    if (!featureFlagClient.boolVariation(RemoveLargeSyncInputs.INSTANCE, new Workspace(replicationActivityInput.getWorkspaceId()))) {
      return replicate(
          replicationActivityInput.getJobRunConfig(),
          replicationActivityInput.getSourceLauncherConfig(),
          replicationActivityInput.getDestinationLauncherConfig(),
          replicationActivityInput.getSyncInput(),
          replicationActivityInput.getTaskQueue());
    }
    metricClient.count(OssMetricsRegistry.ACTIVITY_REPLICATION, 1);
    final Map<String, Object> traceAttributes =
        Map.of(
            ATTEMPT_NUMBER_KEY, replicationActivityInput.getJobRunConfig().getAttemptId(),
            CONNECTION_ID_KEY, replicationActivityInput.getConnectionId(),
            JOB_ID_KEY, replicationActivityInput.getJobRunConfig().getJobId(),
            DESTINATION_DOCKER_IMAGE_KEY, replicationActivityInput.getDestinationLauncherConfig().getDockerImage(),
            SOURCE_DOCKER_IMAGE_KEY, replicationActivityInput.getSourceLauncherConfig().getDockerImage());
    ApmTraceUtils
        .addTagsToTrace(traceAttributes);
    if (replicationActivityInput.getIsReset()) {
      metricClient.count(OssMetricsRegistry.RESET_REQUEST, 1);
    }
    final ActivityExecutionContext context = Activity.getExecutionContext();

    final AtomicReference<Runnable> cancellationCallback = new AtomicReference<>(null);

    return HeartbeatUtils.withBackgroundHeartbeat(
        cancellationCallback,
        () -> {
          final ReplicationInput hydratedReplicationInput = replicationInputHydrator.getHydratedReplicationInput(replicationActivityInput);
          final Worker<ReplicationInput, ReplicationOutput> worker;
          if (featureFlagClient.boolVariation(UseWorkloadApi.INSTANCE,
              new Multi(
                  List.of(new Workspace(replicationActivityInput.getWorkspaceId()), new Connection(replicationActivityInput.getConnectionId()))))) {
            worker = new WorkloadApiWorker(workloadApi, workloadIdGenerator, replicationActivityInput);
          } else {
            final CheckedSupplier<Worker<ReplicationInput, ReplicationOutput>, Exception> workerFactory =
                orchestratorHandleFactory.create(hydratedReplicationInput.getSourceLauncherConfig(),
                    hydratedReplicationInput.getDestinationLauncherConfig(), hydratedReplicationInput.getJobRunConfig(), hydratedReplicationInput,
                    () -> context);
            worker = workerFactory.get();
          }
          cancellationCallback.set(worker::cancel);

          final TemporalAttemptExecution<ReplicationInput, ReplicationOutput> temporalAttempt =
              new TemporalAttemptExecution<>(
                  workspaceRoot,
                  workerEnvironment,
                  logConfigs,
                  hydratedReplicationInput.getJobRunConfig(),
                  worker,
                  hydratedReplicationInput,
                  airbyteApiClient,
                  airbyteVersion,
                  () -> context,
                  Optional.ofNullable(replicationActivityInput.getTaskQueue()));

          final ReplicationOutput attemptOutput = temporalAttempt.get();
          final StandardSyncOutput standardSyncOutput = reduceReplicationOutput(attemptOutput, traceAttributes);

          final String standardSyncOutputString = standardSyncOutput.toString();
          LOGGER.info("sync summary: {}", standardSyncOutputString);
          if (standardSyncOutputString.length() > MAX_TEMPORAL_MESSAGE_SIZE) {
            LOGGER.error("Sync output exceeds the max temporal message size of {}, actual is {}.", MAX_TEMPORAL_MESSAGE_SIZE,
                standardSyncOutputString.length());
          } else {
            LOGGER.info("Sync summary length: {}", standardSyncOutputString.length());
          }
          List<StreamDescriptor> streamsToBackfill = List.of();
          if (replicationActivityInput.getSchemaRefreshOutput() != null) {
            streamsToBackfill = BackfillHelper
                .getStreamsToBackfill(replicationActivityInput.getSchemaRefreshOutput().getAppliedDiff(), hydratedReplicationInput.getCatalog());
          }
          BackfillHelper.markBackfilledStreams(streamsToBackfill, standardSyncOutput);
          LOGGER.info("sync summary after backfill: {}", standardSyncOutput);
          return standardSyncOutput;
        },
        context);
  }

  // Marking task queue as nullable because we changed activity signature; thus runs started before
  // this new change will have taskQueue set to null. We should remove it after the old runs are all
  // finished in next release.
  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public StandardSyncOutput replicate(final JobRunConfig jobRunConfig,
                                      final IntegrationLauncherConfig sourceLauncherConfig,
                                      final IntegrationLauncherConfig destinationLauncherConfig,
                                      final StandardSyncInput syncInput,
                                      @Nullable final String taskQueue) {
    metricClient.count(OssMetricsRegistry.ACTIVITY_REPLICATION, 1);

    final Map<String, Object> traceAttributes =
        Map.of(
            ATTEMPT_NUMBER_KEY, jobRunConfig.getAttemptId(),
            CONNECTION_ID_KEY, syncInput.getConnectionId(),
            JOB_ID_KEY, jobRunConfig.getJobId(),
            DESTINATION_DOCKER_IMAGE_KEY, destinationLauncherConfig.getDockerImage(),
            SOURCE_DOCKER_IMAGE_KEY, sourceLauncherConfig.getDockerImage());
    ApmTraceUtils
        .addTagsToTrace(traceAttributes);
    if (syncInput.getIsReset()) {
      metricClient.count(OssMetricsRegistry.RESET_REQUEST, 1);
    }
    final ActivityExecutionContext context = Activity.getExecutionContext();

    final AtomicReference<Runnable> cancellationCallback = new AtomicReference<>(null);

    return PayloadChecker.validatePayloadSize(HeartbeatUtils.withBackgroundHeartbeat(
        cancellationCallback,
        () -> {
          final var hydratedSyncInput = getHydratedSyncInput(syncInput);
          final var replicationInput =
              getReplicationInputFromSyncInput(hydratedSyncInput, jobRunConfig, sourceLauncherConfig, destinationLauncherConfig);
          final CheckedSupplier<Worker<ReplicationInput, ReplicationOutput>, Exception> workerFactory =
              orchestratorHandleFactory.create(sourceLauncherConfig, destinationLauncherConfig, jobRunConfig, replicationInput, () -> context);
          final var worker = workerFactory.get();
          cancellationCallback.set(worker::cancel);

          final TemporalAttemptExecution<ReplicationInput, ReplicationOutput> temporalAttempt =
              new TemporalAttemptExecution<>(
                  workspaceRoot,
                  workerEnvironment,
                  logConfigs,
                  jobRunConfig,
                  worker,
                  replicationInput,
                  airbyteApiClient,
                  airbyteVersion,
                  () -> context,
                  Optional.ofNullable(taskQueue));

          final ReplicationOutput attemptOutput = temporalAttempt.get();
          final StandardSyncOutput standardSyncOutput = reduceReplicationOutput(attemptOutput, traceAttributes);

          final String standardSyncOutputString = standardSyncOutput.toString();
          LOGGER.info("sync summary: {}", standardSyncOutputString);
          if (standardSyncOutputString.length() > MAX_TEMPORAL_MESSAGE_SIZE) {
            LOGGER.error("Sync output exceeds the max temporal message size of {}, actual is {}.", MAX_TEMPORAL_MESSAGE_SIZE,
                standardSyncOutputString.length());
          } else {
            LOGGER.info("Sync summary length: {}", standardSyncOutputString.length());
          }
          return standardSyncOutput;
        },
        context));
  }

  private StandardSyncInput getHydratedSyncInput(final StandardSyncInput syncInput) {
    final var fullSourceConfig = secretsHydrator.hydrate(syncInput.getSourceConfiguration());
    final var fullDestinationConfig = secretsHydrator.hydrate(syncInput.getDestinationConfiguration());

    final var fullSyncInput = Jsons.clone(syncInput)
        .withSourceConfiguration(fullSourceConfig)
        .withDestinationConfiguration(fullDestinationConfig);

    airbyteConfigValidator.ensureAsRuntime(ConfigSchema.STANDARD_SYNC_INPUT, Jsons.jsonNode(fullSyncInput));
    return fullSyncInput;
  }

  // Simple converter from StandardSyncInput to ReplicationInput.
  // TODO: remove when the workflow version that passes a StandardSyncInput is removed.
  private ReplicationInput getReplicationInputFromSyncInput(final StandardSyncInput hydratedSyncInput,
                                                            final JobRunConfig jobRunConfig,
                                                            final IntegrationLauncherConfig sourceLauncherConfig,
                                                            final IntegrationLauncherConfig destinationLauncherConfig) {
    return new ReplicationInput()
        .withNamespaceDefinition(hydratedSyncInput.getNamespaceDefinition())
        .withNamespaceFormat(hydratedSyncInput.getNamespaceFormat())
        .withPrefix(hydratedSyncInput.getPrefix())
        .withSourceId(hydratedSyncInput.getSourceId())
        .withDestinationId(hydratedSyncInput.getDestinationId())
        .withSourceConfiguration(hydratedSyncInput.getSourceConfiguration())
        .withDestinationConfiguration(hydratedSyncInput.getDestinationConfiguration())
        .withSyncResourceRequirements(hydratedSyncInput.getSyncResourceRequirements())
        .withWorkspaceId(hydratedSyncInput.getWorkspaceId())
        .withConnectionId(hydratedSyncInput.getConnectionId())
        .withNormalizeInDestinationContainer(hydratedSyncInput.getNormalizeInDestinationContainer())
        .withIsReset(hydratedSyncInput.getIsReset())
        .withJobRunConfig(jobRunConfig)
        .withSourceLauncherConfig(sourceLauncherConfig)
        .withDestinationLauncherConfig(destinationLauncherConfig)
        .withCatalog(hydratedSyncInput.getCatalog())
        .withState(hydratedSyncInput.getState());
  }

  private StandardSyncOutput reduceReplicationOutput(final ReplicationOutput output, final Map<String, Object> metricAttributes) {
    final StandardSyncOutput standardSyncOutput = new StandardSyncOutput();
    final StandardSyncSummary syncSummary = new StandardSyncSummary();
    final ReplicationAttemptSummary replicationSummary = output.getReplicationAttemptSummary();

    traceReplicationSummary(replicationSummary, metricAttributes);

    syncSummary.setBytesSynced(replicationSummary.getBytesSynced());
    syncSummary.setRecordsSynced(replicationSummary.getRecordsSynced());
    syncSummary.setStartTime(replicationSummary.getStartTime());
    syncSummary.setEndTime(replicationSummary.getEndTime());
    syncSummary.setStatus(replicationSummary.getStatus());
    syncSummary.setTotalStats(replicationSummary.getTotalStats());
    syncSummary.setStreamStats(replicationSummary.getStreamStats());
    syncSummary.setPerformanceMetrics(output.getReplicationAttemptSummary().getPerformanceMetrics());

    standardSyncOutput.setState(output.getState());
    standardSyncOutput.setOutputCatalog(output.getOutputCatalog());
    standardSyncOutput.setStandardSyncSummary(syncSummary);
    standardSyncOutput.setFailures(output.getFailures());

    return standardSyncOutput;
  }

  private void traceReplicationSummary(final ReplicationAttemptSummary replicationSummary, final Map<String, Object> metricAttributes) {
    if (replicationSummary == null) {
      return;
    }

    final MetricAttribute[] attributes = metricAttributes.entrySet().stream()
        .map(e -> new MetricAttribute(ApmTraceUtils.formatTag(e.getKey()), e.getValue().toString()))
        .collect(Collectors.toSet()).toArray(new MetricAttribute[] {});
    final Map<String, Object> tags = new HashMap<>();
    if (replicationSummary.getBytesSynced() != null) {
      tags.put(REPLICATION_BYTES_SYNCED_KEY, replicationSummary.getBytesSynced());
      metricClient.count(OssMetricsRegistry.REPLICATION_BYTES_SYNCED, replicationSummary.getBytesSynced(), attributes);
    }
    if (replicationSummary.getRecordsSynced() != null) {
      tags.put(REPLICATION_RECORDS_SYNCED_KEY, replicationSummary.getRecordsSynced());
      metricClient.count(OssMetricsRegistry.REPLICATION_RECORDS_SYNCED, replicationSummary.getRecordsSynced(), attributes);
    }
    if (replicationSummary.getStatus() != null) {
      tags.put(REPLICATION_STATUS_KEY, replicationSummary.getStatus().value());
    }
    if (!tags.isEmpty()) {
      ApmTraceUtils.addTagsToTrace(tags);
    }
  }

}
