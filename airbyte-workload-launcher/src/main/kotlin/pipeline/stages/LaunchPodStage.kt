package io.airbyte.workload.launcher.pipeline.stages

import io.airbyte.workload.launcher.pipeline.stages.model.LaunchStage
import io.airbyte.workload.launcher.pipeline.stages.model.LaunchStageIO
import io.airbyte.workload.launcher.pods.KubePodClient
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Named
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

/**
 * Launches the pods for the workload, serializing and passing through input
 * values via the injected pod client.
 */
@Singleton
@Named("launch")
class LaunchPodStage(private val launcher: KubePodClient) : LaunchStage {
  override fun applyStage(input: LaunchStageIO): LaunchStageIO {
    val replInput = input.replicationInput!!

    launcher.launchReplication(replInput, input.msg)

    return input
  }

  override fun getStageName(): StageName {
    return StageName.LAUNCH
  }
}
