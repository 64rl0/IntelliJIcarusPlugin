package org.jetbrains.plugins.icarus.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class SyncFromWorkspaceAction : AnAction() {

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        if (!IcarusActionSupport.tryStartCommand(project)) {
            IcarusActionSupport.notifyCommandAlreadyRunning(project)
            return
        }

        val workspaceRoot = IcarusActionSupport.resolveWorkspaceRoot(project)
            ?: run {
                IcarusActionSupport.finishCommand(project)
                IcarusActionSupport.notify(project, NotificationType.ERROR, "Could not determine workspace root for this project.")
                return
            }

        object : Task.Backgroundable(project, "Sync From Workspace", false) {
            private var pathResolution: PathResolution = PathResolution.Error("Icarus Builder command did not run.")

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running Icarus"

                when (val commandResult = IcarusActionSupport.runCommandInWidget(project, workspaceRoot, WORKSPACE_SYNC_COMMAND)) {
                    is IcarusActionSupport.CommandRunResult.Error -> {
                        pathResolution = PathResolution.Error(commandResult.message)
                    }

                    is IcarusActionSupport.CommandRunResult.Success -> {
                        val failureMessage = IcarusActionSupport.commandFailureMessage(commandResult)
                        if (failureMessage != null) {
                            pathResolution = PathResolution.Error(failureMessage)
                        }
                        else {
                            pathResolution = resolveInterpreterPathFromStdout(commandResult.stdout)
                        }
                    }
                }
            }

            override fun onSuccess() {
                when (val resolution = pathResolution) {
                    is PathResolution.Error -> {
                        IcarusActionSupport.notify(project, NotificationType.ERROR, resolution.message)
                    }

                    is PathResolution.Success -> {
                        when (val configuration = configureProjectInterpreter(project, resolution.path)) {
                            is SdkConfigurationResult.Error -> {
                                IcarusActionSupport.notify(project, NotificationType.ERROR, configuration.message)
                            }

                            is SdkConfigurationResult.Success -> {
                                IcarusActionSupport.notify(
                                    project,
                                    NotificationType.INFORMATION,
                                    "Python interpreter synced to ${configuration.sdk.homePath}",
                                )
                            }
                        }
                    }
                }
            }

            override fun onThrowable(error: Throwable) {
                IcarusActionSupport.notify(project, NotificationType.ERROR, "Icarus sync failed: ${error.message ?: "Unknown error"}")
            }

            override fun onFinished() {
                IcarusActionSupport.finishCommand(project)
            }
        }.queue()
    }

    private fun resolveInterpreterPathFromStdout(stdout: String): PathResolution {
        val rawPath = extractFirstPath(stdout)
            ?: return PathResolution.Error("Icarus returned no interpreter path.")

        val normalizedPath = try {
            Path.of(rawPath.removeSurrounding("\"")).toAbsolutePath().normalize()
        }
        catch (_: InvalidPathException) {
            return PathResolution.Error("Icarus returned an invalid path: $rawPath")
        }

        if (!Files.isRegularFile(normalizedPath)) {
            return PathResolution.Error("Resolved path is not a file: $normalizedPath")
        }

        if (!Files.isExecutable(normalizedPath)) {
            return PathResolution.Error("Resolved path is not executable: $normalizedPath")
        }

        return PathResolution.Success(normalizedPath.toString())
    }

    private fun configureProjectInterpreter(project: Project, interpreterPath: String): SdkConfigurationResult {
        val pythonSdkType = SdkType.findByName(PYTHON_SDK_NAME)
            ?: SdkType.getAllTypeList().firstOrNull { it.javaClass.name == PYTHON_SDK_CLASS }
            ?: return SdkConfigurationResult.Error(
                "Python SDK type is unavailable. Install and enable the Python plugin.",
            )

        val existingSdks = ProjectJdkTable.getInstance().allJdks
        val sdk = SdkConfigurationUtil.findByPath(pythonSdkType, existingSdks, interpreterPath)
            ?: SdkConfigurationUtil.createAndAddSDK(interpreterPath, pythonSdkType)
            ?: return SdkConfigurationResult.Error("Could not create a Python SDK for path: $interpreterPath")

        WriteAction.run<Throwable> {
            ProjectRootManager.getInstance(project).projectSdk = sdk
            ModuleManager.getInstance(project).modules.forEach { module ->
                ModuleRootModificationUtil.setModuleSdk(module, sdk)
            }
        }

        return SdkConfigurationResult.Success(sdk)
    }

    private fun extractFirstPath(stdout: String): String? {
        return stdout
            .lineSequence()
            .flatMap { line -> line.split(':').asSequence() }
            .map { token -> token.trim() }
            .firstOrNull { token -> token.isNotEmpty() }
    }

    private sealed interface PathResolution {
        data class Success(val path: String) : PathResolution
        data class Error(val message: String) : PathResolution
    }

    private sealed interface SdkConfigurationResult {
        data class Success(val sdk: Sdk) : SdkConfigurationResult
        data class Error(val message: String) : SdkConfigurationResult
    }

    companion object {
        private val WORKSPACE_SYNC_COMMAND =
            IcarusActionSupport.buildIcarusBuilderCommand(listOf("path", "devrun_excluderoot.pythonhome"))
        private const val PYTHON_SDK_NAME = "Python SDK"
        private const val PYTHON_SDK_CLASS = "com.jetbrains.python.sdk.PythonSdkType"
    }
}
