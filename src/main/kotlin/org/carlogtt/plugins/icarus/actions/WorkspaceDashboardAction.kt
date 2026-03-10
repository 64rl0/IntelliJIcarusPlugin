package org.carlogtt.plugins.icarus.actions

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import org.carlogtt.plugins.icarus.IcarusBundle
import org.carlogtt.plugins.icarus.toolwindow.IcarusOutputService
import java.nio.file.Files

class WorkspaceDashboardAction : AnAction() {

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabled = project != null && IcarusActionSupport.resolveDetectedWorkspaceRoot(project) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return

        val workspaceRoot = IcarusActionSupport.resolveDetectedWorkspaceRoot(project)
            ?: run {
                IcarusActionSupport.notify(project, NotificationType.ERROR, IcarusBundle.message("icarus.notification.workspaceNotFound"))
                return
            }

        val dashboardPath = workspaceRoot.resolve(".icarus").resolve("report").resolve("index.html")
        if (!Files.isRegularFile(dashboardPath)) {
            IcarusActionSupport.notify(
                project,
                NotificationType.ERROR,
                IcarusBundle.message("icarus.workspace.dashboard.notFound", dashboardPath.toString()),
            )
            return
        }

        BrowserUtil.browse(dashboardPath.toUri().toString())
        project.service<IcarusOutputService>().focusHome()
    }
}
