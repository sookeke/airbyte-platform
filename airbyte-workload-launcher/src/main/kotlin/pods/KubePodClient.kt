package io.airbyte.workload.launcher.pods

import io.airbyte.commons.constants.WorkerConstants.KubeConstants.FULL_POD_TIMEOUT
import io.airbyte.metrics.lib.ApmTraceUtils
import io.airbyte.persistence.job.models.ReplicationInput
import io.airbyte.workers.models.CheckConnectionInput
import io.airbyte.workload.launcher.model.setConnectorLabels
import io.airbyte.workload.launcher.model.setDestinationLabels
import io.airbyte.workload.launcher.model.setSourceLabels
import io.airbyte.workload.launcher.pipeline.consumer.LauncherInput
import io.fabric8.kubernetes.api.model.Pod
import io.micronaut.context.annotation.Requires
import io.micronaut.context.env.Environment
import jakarta.inject.Singleton
import java.lang.RuntimeException
import java.time.Duration
import java.util.UUID

/**
 * Interface layer between domain and Kube layers.
 * Composes raw Kube layer atomic operations to perform business operations.
 */
@Singleton
@Requires(env = [Environment.KUBERNETES])
class KubePodClient(
  private val orchestratorLauncher: OrchestratorPodLauncher,
  private val labeler: PodLabeler,
  private val mapper: PayloadKubeInputMapper,
) : PodClient {
  override fun podsExistForAutoId(autoId: UUID): Boolean {
    return orchestratorLauncher.podsExist(labeler.getAutoIdLabels(autoId))
  }

  override fun launchReplication(
    replicationInput: ReplicationInput,
    launcherInput: LauncherInput,
  ) {
    val sharedLabels = labeler.getSharedLabels(launcherInput.workloadId, launcherInput.mutexKey, launcherInput.labels, launcherInput.autoId)

    val inputWithLabels =
      replicationInput
        .setSourceLabels(sharedLabels)
        .setDestinationLabels(sharedLabels)

    val kubeInput = mapper.toKubeInput(inputWithLabels, sharedLabels)

    val pod: Pod
    try {
      pod =
        orchestratorLauncher.create(
          kubeInput.orchestratorLabels,
          kubeInput.resourceReqs,
          kubeInput.nodeSelectors,
          kubeInput.kubePodInfo,
          kubeInput.annotations,
        )
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Failed to create pod ${kubeInput.kubePodInfo.name}.",
        e,
      )
    }

    try {
      orchestratorLauncher.waitForPodInit(kubeInput.orchestratorLabels, ORCHESTRATOR_INIT_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Orchestrator pod failed to start within allotted timeout.",
        e,
      )
    }

    try {
      orchestratorLauncher.copyFilesToKubeConfigVolumeMain(pod, kubeInput.fileMap)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Failed to copy files to orchestrator pod ${kubeInput.kubePodInfo.name}.",
        e,
      )
    }

    try {
      orchestratorLauncher.waitForPodReadyOrTerminal(kubeInput.sourceLabels, CONNECTOR_STARTUP_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Source pod failed to start within allotted timeout.",
        e,
      )
    }

    try {
      orchestratorLauncher.waitForPodReadyOrTerminal(kubeInput.destinationLabels, CONNECTOR_STARTUP_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Destination pod failed to start within allotted timeout.",
        e,
      )
    }
  }

  override fun launchCheck(
    checkInput: CheckConnectionInput,
    launcherInput: LauncherInput,
  ) {
    // For check the workload id is too long to be store as a kube label thus it is not added
    val sharedLabels =
      labeler.getSharedLabels(
        workloadId = null,
        mutexKey = launcherInput.mutexKey,
        passThroughLabels = launcherInput.labels,
        autoId = launcherInput.autoId,
      )

    val inputWithLabels = checkInput.setConnectorLabels(sharedLabels)

    val kubeInput = mapper.toKubeInput(inputWithLabels, sharedLabels)

    val pod: Pod
    try {
      pod =
        orchestratorLauncher.create(
          kubeInput.orchestratorLabels,
          kubeInput.resourceReqs,
          kubeInput.nodeSelectors,
          kubeInput.kubePodInfo,
          kubeInput.annotations,
        )
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Failed to create pod ${kubeInput.kubePodInfo.name}.",
        e,
      )
    }

    try {
      orchestratorLauncher.waitForPodInit(kubeInput.orchestratorLabels, ORCHESTRATOR_INIT_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Orchestrator pod failed to start within allotted timeout.",
        e,
      )
    }

    try {
      orchestratorLauncher.copyFilesToKubeConfigVolumeMain(pod, kubeInput.fileMap)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Failed to copy files to orchestrator pod ${kubeInput.kubePodInfo.name}.",
        e,
      )
    }

    try {
      orchestratorLauncher.waitForPodReadyOrTerminal(kubeInput.connectorLabels, CONNECTOR_STARTUP_TIMEOUT_VALUE)
    } catch (e: RuntimeException) {
      ApmTraceUtils.addExceptionToTrace(e)
      throw KubePodInitException(
        "Connector pod failed to start within allotted timeout.",
        e,
      )
    }
  }

  override fun deleteMutexPods(mutexKey: String): Boolean {
    val labels = labeler.getMutexLabels(mutexKey)
    val deleted = orchestratorLauncher.deleteActivePods(labels)

    return deleted.isNotEmpty()
  }

  companion object {
    private val TIMEOUT_SLACK: Duration = Duration.ofSeconds(5)
    val CONNECTOR_STARTUP_TIMEOUT_VALUE: Duration = FULL_POD_TIMEOUT.plus(TIMEOUT_SLACK)
    val ORCHESTRATOR_INIT_TIMEOUT_VALUE: Duration = Duration.ofMinutes(5)
  }
}
