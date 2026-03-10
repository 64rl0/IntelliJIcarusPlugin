package org.jetbrains.plugins.icarus.toolwindow

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class IcarusToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        project.service<IcarusOutputService>().initialize(toolWindow)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        return true
    }
}
