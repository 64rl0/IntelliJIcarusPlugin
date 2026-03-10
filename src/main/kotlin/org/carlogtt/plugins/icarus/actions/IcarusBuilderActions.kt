package org.carlogtt.plugins.icarus.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import org.carlogtt.plugins.icarus.IcarusBundle

sealed class IcarusBuilderCommandAction(
    private val builderArguments: List<String>,
) : AnAction() {

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabled =
            project != null &&
            !IcarusActionSupport.isCommandRunning(project) &&
            IcarusActionSupport.resolveDetectedWorkspaceRoot(project) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        if (!IcarusActionSupport.tryStartCommand(project)) {
            IcarusActionSupport.notifyCommandAlreadyRunning(project)
            return
        }

        val workspaceRoot = IcarusActionSupport.resolveDetectedWorkspaceRoot(project)
            ?.toString()
            ?: run {
                IcarusActionSupport.finishCommand(project)
                IcarusActionSupport.notify(project, NotificationType.ERROR, IcarusBundle.message("icarus.notification.workspaceNotFound"))
                return
            }

        val command = IcarusActionSupport.buildIcarusBuilderCommand(builderArguments)
        val commandLine = IcarusActionSupport.toCommandLine(command)

        object : Task.Backgroundable(project, commandLine, false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = IcarusBundle.message("icarus.status.runningBuilder")
                IcarusActionSupport.runCommandInWidget(project, workspaceRoot, command)
            }

            override fun onFinished() {
                IcarusActionSupport.finishCommand(project)
            }
        }.queue()
    }
}

class IcarusBuilderBuildAction : IcarusBuilderCommandAction(BUILD_ARGUMENTS)

class IcarusBuilderReleaseAction : IcarusBuilderCommandAction(RELEASE_ARGUMENTS)

class IcarusBuilderFormatAction : IcarusBuilderCommandAction(FORMAT_ARGUMENTS)

class IcarusBuilderTestAction : IcarusBuilderCommandAction(TEST_ARGUMENTS)

class IcarusBuilderDocsAction : IcarusBuilderCommandAction(DOCS_ARGUMENTS)

class IcarusBuilderCleanAction : IcarusBuilderCommandAction(CLEAN_ARGUMENTS)

class IcarusBuilderMergeAction : IcarusBuilderCommandAction(MERGE_ARGUMENTS)

private val BUILD_ARGUMENTS = listOf("build")
private val RELEASE_ARGUMENTS = listOf("release")
private val FORMAT_ARGUMENTS = listOf("format")
private val TEST_ARGUMENTS = listOf("test")
private val DOCS_ARGUMENTS = listOf("docs")
private val CLEAN_ARGUMENTS = listOf("clean")
private val MERGE_ARGUMENTS = listOf("merge")
