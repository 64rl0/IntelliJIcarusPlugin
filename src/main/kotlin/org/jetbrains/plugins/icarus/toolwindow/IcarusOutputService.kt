package org.jetbrains.plugins.icarus.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import org.jetbrains.plugins.icarus.actions.IcarusActionSupport
import org.jetbrains.plugins.icarus.IcarusBundle
import org.jetbrains.plugins.icarus.IcarusEnvironment
import java.awt.BorderLayout
import java.awt.Font
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

@Service(Service.Level.PROJECT)
class IcarusOutputService(
    private val project: Project,
) {

    class OutputSession internal constructor(
        internal val toolWindow: ToolWindow,
        internal val consoleView: ConsoleView,
        internal val tabTitle: String,
    )

    @Volatile
    private var toolWindow: ToolWindow? = null

    @Volatile
    private var homeStatusLabel: JLabel? = null

    @Volatile
    private var homeActionStatusLabel: JLabel? = null

    @Volatile
    private var workspaceStatusIconLabel: JLabel? = null

    @Volatile
    private var workspaceStatusTextLabel: JLabel? = null

    private val runCounter = AtomicInteger(0)
    private val refreshHomeContentAction = RefreshHomeContentAction()

    fun initialize(toolWindow: ToolWindow) {
        runOnEdtAndWait {
            this.toolWindow = toolWindow
            toolWindow.setTitleActions(listOf(refreshHomeContentAction))
            ensureHomeContent(toolWindow)
        }
    }

    fun startRun(commandLine: String, tabTitleBaseOverride: String? = null): OutputSession? {
        return runOnEdtAndWait {
            val window = resolveToolWindow() ?: return@runOnEdtAndWait null
            val runIndex = runCounter.incrementAndGet()
            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
            val runTabTitle = if (tabTitleBaseOverride.isNullOrBlank()) {
                contentTitle(commandLine, runIndex)
            }
            else {
                "$tabTitleBaseOverride #$runIndex"
            }
            val content = ContentFactory.getInstance().createContent(
                console.component,
                runTabTitle,
                false,
            )
            content.isCloseable = true

            window.contentManager.addContent(content, preferredRunTabIndex(window))
            window.contentManager.setSelectedContent(content, true)
            window.show(null)

            console.print("$commandLine\n\n", ConsoleViewContentType.USER_INPUT)
            OutputSession(window, console, runTabTitle)
        }
    }

    fun appendStdOut(session: OutputSession, text: String) {
        append(session, text, ConsoleViewContentType.NORMAL_OUTPUT)
    }

    fun appendUserInput(session: OutputSession, text: String) {
        append(session, text, ConsoleViewContentType.USER_INPUT)
    }

    fun appendProcessChunk(session: OutputSession, text: String, outputType: Key<*>, ansiEscapeDecoder: AnsiEscapeDecoder) {
        if (text.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().invokeLater(
            {
                session.toolWindow.show(null)
                ansiEscapeDecoder.escapeText(text, outputType) { chunk, attributes ->
                    session.consoleView.print(chunk, ConsoleViewContentType.getConsoleViewType(attributes))
                }
            },
            project.disposed,
        )
    }

    fun appendStdErr(session: OutputSession, text: String) {
        append(session, text, ConsoleViewContentType.ERROR_OUTPUT)
    }

    fun appendSystem(session: OutputSession, text: String) {
        append(session, text, ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private fun append(session: OutputSession, text: String, contentType: ConsoleViewContentType) {
        if (text.isEmpty()) {
            return
        }

        ApplicationManager.getApplication().invokeLater(
            {
                session.toolWindow.show(null)
                session.consoleView.print(text, contentType)
            },
            project.disposed,
        )
    }

    private fun resolveToolWindow(): ToolWindow? {
        val existingWindow = toolWindow
        if (existingWindow != null) {
            ensureHomeContent(existingWindow)
            return existingWindow
        }

        val resolvedWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
        initialize(resolvedWindow)
        return resolvedWindow
    }

    private fun ensureHomeContent(toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val existingHomeContent = contentManager.contents.firstOrNull { content ->
            content.getUserData(HOME_CONTENT_KEY) == true
        }
        if (existingHomeContent != null) {
            homeStatusLabel = existingHomeContent.getUserData(HOME_STATUS_LABEL_KEY)
            homeActionStatusLabel = existingHomeContent.getUserData(HOME_ACTION_STATUS_LABEL_KEY)
            workspaceStatusIconLabel = existingHomeContent.getUserData(WORKSPACE_STATUS_ICON_LABEL_KEY)
            workspaceStatusTextLabel = existingHomeContent.getUserData(WORKSPACE_STATUS_TEXT_LABEL_KEY)
            return
        }

        val homeContentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(12)
            isOpaque = false
        }

        val titleLabel = JLabel(IcarusBundle.message("icarus.widget.homeTitle")).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 5f)
            alignmentX = 0.0f
        }
        homeContentPanel.add(titleLabel)
        homeContentPanel.add(Box.createVerticalStrut(8))

        val introLabel = JLabel(IcarusBundle.message("icarus.widget.initialMessage")).apply {
            alignmentX = 0.0f
        }
        homeContentPanel.add(introLabel)
        homeContentPanel.add(Box.createVerticalStrut(10))

        val statusLabel = JLabel().apply {
            foreground = JBColor.RED
            alignmentX = 0.0f
            iconTextGap = 6
        }
        homeContentPanel.add(statusLabel)
        homeContentPanel.add(Box.createVerticalStrut(6))

        val actionStatusLabel = JLabel().apply {
            foreground = JBColor.RED
            alignmentX = 0.0f
            iconTextGap = 6
            icon = AllIcons.RunConfigurations.TestError
            isVisible = false
        }
        homeContentPanel.add(actionStatusLabel)
        homeContentPanel.add(Box.createVerticalStrut(14))

        homeContentPanel.add(sectionLabel(IcarusBundle.message("icarus.widget.section.workspace")))
        homeContentPanel.add(Box.createVerticalStrut(6))

        val workspaceStatusIcon = JLabel(AllIcons.RunConfigurations.TestError).apply {
            alignmentY = 0.5f
        }
        val workspaceStatusText = JLabel(IcarusBundle.message("icarus.widget.workspace.notDetected")).apply {
            foreground = JBColor.RED
            alignmentY = 0.5f
        }
        val workspaceStatusPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = 0.0f
            add(workspaceStatusIcon)
            add(Box.createHorizontalStrut(6))
            add(workspaceStatusText)
        }
        homeContentPanel.add(workspaceStatusPanel)
        homeContentPanel.add(Box.createVerticalStrut(6))

        val workspaceActionsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = 0.0f
            add(actionButton(IcarusBundle.message("icarus.action.syncFromWorkspace"), ACTION_SYNC_FROM_WORKSPACE_ID))
            add(Box.createHorizontalStrut(8))
            add(actionButton(IcarusBundle.message("icarus.action.mergeUserSpace"), ACTION_MERGE_USERSPACE_ID))
        }
        homeContentPanel.add(workspaceActionsRow)
        homeContentPanel.add(Box.createVerticalStrut(14))

        homeContentPanel.add(sectionLabel(IcarusBundle.message("icarus.widget.section.builder")))
        homeContentPanel.add(Box.createVerticalStrut(6))

        val builderActionsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = 0.0f
            add(actionButton(IcarusBundle.message("icarus.builder.action.release"), ACTION_BUILDER_RELEASE_ID))
            add(Box.createHorizontalStrut(8))
            add(actionButton(IcarusBundle.message("icarus.builder.action.build"), ACTION_BUILDER_BUILD_ID))
            add(Box.createHorizontalStrut(8))
            add(actionButton(IcarusBundle.message("icarus.builder.action.format"), ACTION_BUILDER_FORMAT_ID))
            add(Box.createHorizontalStrut(8))
            add(actionButton(IcarusBundle.message("icarus.builder.action.test"), ACTION_BUILDER_TEST_ID))
            add(Box.createHorizontalStrut(8))
            add(actionButton(IcarusBundle.message("icarus.builder.action.docs"), ACTION_BUILDER_DOCS_ID))
            add(Box.createHorizontalStrut(8))
            add(actionButton(IcarusBundle.message("icarus.builder.action.clean"), ACTION_BUILDER_CLEAN_ID))
        }
        homeContentPanel.add(builderActionsRow)

        val homePanel = JPanel(BorderLayout()).apply {
            isFocusable = true
            add(homeContentPanel, BorderLayout.NORTH)
        }

        val homeContent = ContentFactory.getInstance().createContent(homePanel, HOME_TAB_TITLE, false)
        homeContent.isCloseable = false
        homeContent.setPreferredFocusableComponent(homePanel)
        homeContent.putUserData(HOME_CONTENT_KEY, true)
        homeContent.putUserData(HOME_STATUS_LABEL_KEY, statusLabel)
        homeContent.putUserData(HOME_ACTION_STATUS_LABEL_KEY, actionStatusLabel)
        homeContent.putUserData(WORKSPACE_STATUS_ICON_LABEL_KEY, workspaceStatusIcon)
        homeContent.putUserData(WORKSPACE_STATUS_TEXT_LABEL_KEY, workspaceStatusText)
        contentManager.addContent(homeContent)

        this.homeStatusLabel = statusLabel
        this.homeActionStatusLabel = actionStatusLabel
        this.workspaceStatusIconLabel = workspaceStatusIcon
        this.workspaceStatusTextLabel = workspaceStatusText
        refreshHomeContent()
    }

    fun showHomeActionStatus(message: String) {
        runOnEdtAndWait {
            val actionLabel = resolveHomeActionStatusLabel() ?: return@runOnEdtAndWait
            actionLabel.text = message
            actionLabel.isVisible = true
            focusHomeContent()
        }
    }

    fun clearHomeActionStatus() {
        runOnEdtAndWait {
            val actionLabel = resolveHomeActionStatusLabel() ?: return@runOnEdtAndWait
            val wasVisible = actionLabel.isVisible || actionLabel.text.isNotBlank()
            actionLabel.text = ""
            actionLabel.isVisible = false

            if (wasVisible) {
                focusHomeContent()
            }
        }
    }

    private fun resolveHomeActionStatusLabel(): JLabel? {
        val homeContent = resolveHomeContent() ?: return homeActionStatusLabel
        val label = homeContent.getUserData(HOME_ACTION_STATUS_LABEL_KEY)
        if (label != null) {
            homeActionStatusLabel = label
        }
        return label
    }

    private fun focusHomeContent() {
        val toolWindow = resolveToolWindow() ?: return
        val contentManager = toolWindow.contentManager
        val homeContent = resolveHomeContent() ?: return

        contentManager.setSelectedContent(homeContent, true)
        toolWindow.show(null)

        val focusComponent = homeContent.preferredFocusableComponent ?: homeContent.component
        val focusManager = IdeFocusManager.getInstance(project)
        ApplicationManager.getApplication().invokeLater(
            { focusManager.requestFocus(focusComponent, true) },
            project.disposed,
        )
    }

    private fun resolveHomeContent(): Content? {
        val toolWindow = resolveToolWindow() ?: return null
        return toolWindow.contentManager.contents.firstOrNull { content ->
            content.getUserData(HOME_CONTENT_KEY) == true
        }
    }

    private fun refreshHomeContent() {
        runOnEdtAndWait {
            val statusLabel = homeStatusLabel ?: return@runOnEdtAndWait
            val actionStatusLabel = homeActionStatusLabel ?: return@runOnEdtAndWait
            val workspaceIconLabel = workspaceStatusIconLabel ?: return@runOnEdtAndWait
            val workspaceTextLabel = workspaceStatusTextLabel ?: return@runOnEdtAndWait
            val executableHomeInfo = IcarusEnvironment.executableHomeInfo()

            actionStatusLabel.text = ""
            actionStatusLabel.isVisible = false

            if (executableHomeInfo.executableAvailable) {
                statusLabel.icon = null
                statusLabel.text = ""
                statusLabel.isVisible = false
            }
            else {
                statusLabel.icon = AllIcons.RunConfigurations.TestError
                val expectedExecutablePath = executableHomeInfo.expectedExecutablePath
                statusLabel.text = if (expectedExecutablePath != null) {
                    IcarusBundle.message("icarus.widget.executableNotFound", expectedExecutablePath.toString())
                }
                else {
                    IcarusBundle.message("icarus.widget.homeNotResolved")
                }
                statusLabel.isVisible = true
            }

            val workspaceRoot = IcarusActionSupport.resolveWorkspaceRoot(project)
            val workspaceConfigPath = workspaceRoot
                ?.let { root ->
                    try {
                        Path.of(root, WORKSPACE_CONFIG_FILE_NAME)
                    }
                    catch (_: InvalidPathException) {
                        null
                    }
                }

            if (workspaceConfigPath != null && Files.isRegularFile(workspaceConfigPath)) {
                workspaceIconLabel.icon = AllIcons.RunConfigurations.TestPassed
                workspaceTextLabel.foreground = JBColor(
                    java.awt.Color(0x1A7F37),
                    java.awt.Color(0x59D185),
                )
                val workspacePath = workspaceConfigPath.parent?.toString() ?: workspaceConfigPath.toString()
                workspaceTextLabel.text = IcarusBundle.message(
                    "icarus.widget.workspace.detected",
                    workspacePath,
                )
            }
            else {
                workspaceIconLabel.icon = AllIcons.RunConfigurations.TestError
                workspaceTextLabel.foreground = JBColor.RED
                workspaceTextLabel.text = IcarusBundle.message("icarus.widget.workspace.notDetected")
            }
        }
    }

    private fun sectionLabel(text: String): JLabel {
        return JLabel(text).apply {
            font = font.deriveFont(Font.BOLD)
            alignmentX = 0.0f
        }
    }

    private fun actionButton(text: String, actionId: String): JButton {
        return JButton(text).apply {
            alignmentX = 0.0f
            isDefaultCapable = false

            addActionListener {
                if (IcarusActionSupport.isCommandRunning(project)) {
                    IcarusActionSupport.notifyCommandAlreadyRunning(project)
                    return@addActionListener
                }

                val action = ActionManager.getInstance().getAction(actionId) ?: return@addActionListener
                ActionManager.getInstance().tryToExecute(
                    action,
                    null,
                    this,
                    ActionPlaces.TOOLWINDOW_CONTENT,
                    true,
                )
            }
        }
    }

    private inner class RefreshHomeContentAction : DumbAwareAction(
        IcarusBundle.message("icarus.widget.refresh"),
        IcarusBundle.message("icarus.widget.refresh"),
        AllIcons.Actions.Refresh,
    ) {
        override fun actionPerformed(event: AnActionEvent) {
            refreshHomeContent()
        }
    }

    private fun contentTitle(commandLine: String, runIndex: Int): String {
        val normalizedCommand = if (commandLine == WORKSPACE_SYNC_COMMAND_LINE) {
            WORKSPACE_SYNC_TAB_TITLE
        }
        else {
            val commandSuffix = commandLine.removePrefix("icarus builder ").ifBlank { commandLine }
            commandSuffix.replaceFirstChar { firstChar ->
                if (firstChar.isLowerCase()) firstChar.titlecase() else firstChar.toString()
            }
        }

        return "$normalizedCommand #$runIndex"
    }

    private fun preferredRunTabIndex(toolWindow: ToolWindow): Int {
        val contentManager = toolWindow.contentManager
        if (contentManager.contentCount == 0) {
            return 0
        }

        val homeIndex = contentManager.contents.indexOfFirst { content ->
            content.getUserData(HOME_CONTENT_KEY) == true
        }

        return if (homeIndex >= 0) homeIndex + 1 else contentManager.contentCount
    }

    private fun <T> runOnEdtAndWait(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }

        var result: T? = null
        application.invokeAndWait { result = action() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    companion object {
        const val TOOL_WINDOW_ID = "Icarus"
        private val HOME_CONTENT_KEY = Key.create<Boolean>("icarus.toolwindow.home")
        private val HOME_STATUS_LABEL_KEY = Key.create<JLabel>("icarus.toolwindow.home.status")
        private val HOME_ACTION_STATUS_LABEL_KEY = Key.create<JLabel>("icarus.toolwindow.home.action.status")
        private val WORKSPACE_STATUS_ICON_LABEL_KEY = Key.create<JLabel>("icarus.toolwindow.workspace.icon")
        private val WORKSPACE_STATUS_TEXT_LABEL_KEY = Key.create<JLabel>("icarus.toolwindow.workspace.text")
        private const val HOME_TAB_TITLE = "Home"
        private const val WORKSPACE_CONFIG_FILE_NAME = "icarus.cfg"
        private const val ACTION_SYNC_FROM_WORKSPACE_ID = "Icarus.SyncFromWorkspace"
        private const val ACTION_MERGE_USERSPACE_ID = "Icarus.Builder.MergeUserSpace"
        private const val ACTION_BUILDER_RELEASE_ID = "Icarus.Builder.Release"
        private const val ACTION_BUILDER_BUILD_ID = "Icarus.Builder.Build"
        private const val ACTION_BUILDER_FORMAT_ID = "Icarus.Builder.Format"
        private const val ACTION_BUILDER_TEST_ID = "Icarus.Builder.Test"
        private const val ACTION_BUILDER_DOCS_ID = "Icarus.Builder.Docs"
        private const val ACTION_BUILDER_CLEAN_ID = "Icarus.Builder.Clean"
        private const val WORKSPACE_SYNC_COMMAND_LINE = "icarus builder path devrun_excluderoot.pythonhome"
        private const val WORKSPACE_SYNC_TAB_TITLE = "SyncWs"
    }
}
