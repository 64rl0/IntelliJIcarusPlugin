package org.carlogtt.plugins.icarus.actions

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import org.carlogtt.plugins.icarus.IcarusEnvironment
import org.carlogtt.plugins.icarus.toolwindow.IcarusOutputService
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal object IcarusActionSupport {

    sealed interface CommandRunResult {
        data class Success(
            val commandLine: String,
            val stdout: String,
            val stderr: String,
            val exitCode: Int,
        ) : CommandRunResult

        data class Error(val message: String) : CommandRunResult
    }

    private const val NOTIFICATION_GROUP_ID = "Icarus"
    private val ICARUS_BUILDER_BASE_COMMAND = listOf("icarus", "builder")

    fun resolveWorkspaceRoot(project: Project): String? {
        project.basePath?.takeIf(::isExistingDirectory)?.let { return it }
        project.guessProjectDir()?.path?.takeIf(::isExistingDirectory)?.let { return it }

        return ProjectRootManager.getInstance(project).contentRoots
            .asSequence()
            .map { it.path }
            .firstOrNull(::isExistingDirectory)
    }

    fun resolveDetectedWorkspaceRoot(project: Project): Path? {
        val workspaceRoot = resolveWorkspaceRoot(project) ?: return null
        val rootPath = try {
            Path.of(workspaceRoot)
        }
        catch (_: InvalidPathException) {
            return null
        }

        val workspaceConfigPath = rootPath.resolve(WORKSPACE_CONFIG_FILE_NAME)
        return if (Files.isRegularFile(workspaceConfigPath)) rootPath else null
    }

    fun buildIcarusBuilderCommand(arguments: List<String>): List<String> {
        return ICARUS_BUILDER_BASE_COMMAND + arguments
    }

    fun toCommandLine(command: List<String>): String {
        return command.joinToString(" ")
    }

    fun createRunSession(
        project: Project,
        header: String,
        tabTitleBaseOverride: String? = null,
    ): IcarusOutputService.OutputSession? {
        saveAllOpenDocuments()

        val outputService = project.service<IcarusOutputService>()
        val outputSession = outputService.startRun(header, tabTitleBaseOverride) ?: return null
        setRunningTabTitle(project, outputSession.tabTitle)
        outputService.clearHomeActionStatus()
        return outputSession
    }

    fun runCommandInWidget(
        project: Project,
        workspaceRoot: String,
        command: List<String>,
        showStderrInWidget: Boolean = true,
    ): CommandRunResult {
        val commandLine = toCommandLine(command)
        val outputSession = createRunSession(project, commandLine)
            ?: return CommandRunResult.Error("Icarus output widget is unavailable.")

        return executeCommandInSession(
            project = project,
            workspaceRoot = workspaceRoot,
            command = command,
            outputSession = outputSession,
            includeCommandHeader = false,
            showStderrInWidget = showStderrInWidget,
        )
    }

    fun runCommandInSession(
        project: Project,
        workspaceRoot: String,
        command: List<String>,
        outputSession: IcarusOutputService.OutputSession,
        includeCommandHeader: Boolean = true,
        showStderrInWidget: Boolean = true,
    ): CommandRunResult {
        return executeCommandInSession(
            project = project,
            workspaceRoot = workspaceRoot,
            command = command,
            outputSession = outputSession,
            includeCommandHeader = includeCommandHeader,
            showStderrInWidget = showStderrInWidget,
        )
    }

    private fun executeCommandInSession(
        project: Project,
        workspaceRoot: String,
        command: List<String>,
        outputSession: IcarusOutputService.OutputSession,
        includeCommandHeader: Boolean,
        showStderrInWidget: Boolean,
    ): CommandRunResult {
        val outputService = project.service<IcarusOutputService>()
        val commandLine = toCommandLine(command)

        if (includeCommandHeader) {
            outputService.appendUserInput(outputSession, "$commandLine\n\n")
        }

        val workspacePath = try {
            Path.of(workspaceRoot)
        }
        catch (_: InvalidPathException) {
            val message = "Workspace root is invalid: $workspaceRoot"
            outputService.appendStdErr(outputSession, "$message\n")
            return CommandRunResult.Error(message)
        }

        if (command.isEmpty()) {
            val message = "Command is empty."
            outputService.appendStdErr(outputSession, "$message\n")
            return CommandRunResult.Error(message)
        }

        val expectedIcarusExecutablePath = IcarusEnvironment.expectedIcarusExecutablePath()
            ?: run {
                val message = "Could not resolve HOME directory for Icarus executable path."
                outputService.appendStdErr(outputSession, "$message\n")
                return CommandRunResult.Error(message)
            }

        val icarusExecutablePath = IcarusEnvironment.resolveIcarusExecutablePath()
            ?: run {
                val message = "Icarus binary is missing or not executable at $expectedIcarusExecutablePath"
                outputService.appendStdErr(outputSession, "$message\n")
                return CommandRunResult.Error(message)
            }

        val commandForExecution = commandWithResolvedIcarusPath(command, icarusExecutablePath)

        val process = try {
            val processBuilder = ProcessBuilder(commandForExecution)
                .directory(workspacePath.toFile())
                .redirectErrorStream(false)
            augmentExecutionPath(processBuilder.environment(), workspacePath)
            processBuilder.start()
        }
        catch (exception: IOException) {
            val message = "Failed to run `$commandLine`: ${exception.message ?: "Unknown error"}"
            outputService.appendStdErr(outputSession, "$message\n")
            return CommandRunResult.Error(message)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val ansiEscapeDecoder = AnsiEscapeDecoder()

        val stdoutReader = thread(start = true, isDaemon = true, name = "icarus-widget-stdout") {
            process.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(STREAM_READ_BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) {
                        break
                    }

                    val chunk = String(buffer, 0, count)
                    stdout.append(chunk)
                    outputService.appendProcessChunk(outputSession, chunk, ProcessOutputTypes.STDOUT, ansiEscapeDecoder)
                }
            }
        }

        val stderrReader = thread(start = true, isDaemon = true, name = "icarus-widget-stderr") {
            process.errorStream.bufferedReader().use { reader ->
                val buffer = CharArray(STREAM_READ_BUFFER_SIZE)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) {
                        break
                    }

                    val chunk = String(buffer, 0, count)
                    stderr.append(chunk)
                    if (showStderrInWidget) {
                        outputService.appendProcessChunk(outputSession, chunk, ProcessOutputTypes.STDERR, ansiEscapeDecoder)
                    }
                }
            }
        }

        val exitCode = try {
            process.waitFor()
        }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            process.destroyForcibly()
            val message = "Command was interrupted: $commandLine"
            outputService.appendStdErr(outputSession, "$message\n")
            return CommandRunResult.Error(message)
        }

        stdoutReader.join()
        stderrReader.join()

        return CommandRunResult.Success(
            commandLine = commandLine,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
            exitCode = exitCode,
        )
    }

    fun commandFailureMessage(result: CommandRunResult.Success): String? {
        if (result.exitCode != 1) {
            return null
        }

        val details = stripAnsiEscapeSequences(result.stderr).trim().ifBlank { "no stderr output" }
        return "`${result.commandLine}` failed: $details"
    }

    fun notify(project: Project, type: NotificationType, content: String) {
        Notification(NOTIFICATION_GROUP_ID, "Icarus", content, type).notify(project)
    }

    fun notifyCommandAlreadyRunning(project: Project) {
        val runningTabTitle = currentRunningTabTitle(project)
        val message = if (runningTabTitle.isNullOrBlank()) {
            COMMAND_ALREADY_RUNNING_MESSAGE
        }
        else {
            "$COMMAND_ALREADY_RUNNING_MESSAGE Running tab: $runningTabTitle."
        }
        project.service<IcarusOutputService>().showHomeActionStatus(message)
    }

    fun tryStartCommand(project: Project): Boolean {
        return project.service<IcarusCommandStateService>().tryAcquire()
    }

    fun finishCommand(project: Project) {
        project.service<IcarusCommandStateService>().release()
        project.service<IcarusOutputService>().clearHomeActionStatus()
    }

    fun isCommandRunning(project: Project): Boolean {
        return project.service<IcarusCommandStateService>().isRunning()
    }

    private fun setRunningTabTitle(project: Project, tabTitle: String) {
        project.service<IcarusCommandStateService>().setRunningTabTitle(tabTitle)
    }

    private fun currentRunningTabTitle(project: Project): String? {
        return project.service<IcarusCommandStateService>().runningTabTitle()
    }

    private fun isExistingDirectory(path: String): Boolean {
        return try {
            Files.isDirectory(Path.of(path))
        }
        catch (_: InvalidPathException) {
            false
        }
    }

    private fun commandWithResolvedIcarusPath(command: List<String>, icarusExecutablePath: Path): List<String> {
        if (command.firstOrNull() != ICARUS_EXECUTABLE_NAME) {
            return command
        }

        return listOf(icarusExecutablePath.toString()) + command.drop(1)
    }

    private fun stripAnsiEscapeSequences(text: String): String {
        return ANSI_ESCAPE_SEQUENCE_REGEX.replace(text, "")
    }

    private fun saveAllOpenDocuments() {
        val application = ApplicationManager.getApplication()
        val saveAction = {
            FileDocumentManager.getInstance().saveAllDocuments()
        }

        if (application.isDispatchThread) {
            saveAction()
            return
        }

        application.invokeAndWait {
            saveAction()
        }
    }

    private fun augmentExecutionPath(environment: MutableMap<String, String>, workspacePath: Path) {
        val interactiveShellPath = resolveInteractiveShellPath(environment, workspacePath) ?: return
        environment[PATH_ENVIRONMENT_VARIABLE] = interactiveShellPath
    }

    private fun resolveInteractiveShellPath(environment: Map<String, String>, workspacePath: Path): String? {
        val shellExecutable = environment[SHELL_ENVIRONMENT_VARIABLE]
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv(SHELL_ENVIRONMENT_VARIABLE)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LOGIN_SHELL

        val process = try {
            ProcessBuilder(shellExecutable, "-ilc", INTERACTIVE_PATH_PROBE_COMMAND)
                .directory(workspacePath.toFile())
                .redirectErrorStream(false)
                .start()
        }
        catch (_: Exception) {
            return null
        }

        val stdout = process.inputStream.bufferedReader().use { it.readText() }
        process.errorStream.bufferedReader().use { it.readText() }

        val completed = try {
            process.waitFor(SHELL_PATH_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        if (!completed) {
            process.destroyForcibly()
            return null
        }

        if (process.exitValue() != 0) {
            return null
        }

        return extractInteractiveShellPath(stdout)
    }

    private fun extractInteractiveShellPath(stdout: String): String? {
        val startIndex = stdout.indexOf(INTERACTIVE_PATH_PROBE_PREFIX)
        if (startIndex < 0) {
            return null
        }

        val valueStart = startIndex + INTERACTIVE_PATH_PROBE_PREFIX.length
        val endIndex = stdout.indexOf(INTERACTIVE_PATH_PROBE_SUFFIX, valueStart)
        if (endIndex < 0 || endIndex <= valueStart) {
            return null
        }

        val pathValue = stdout.substring(valueStart, endIndex).trim()
        return pathValue.takeIf { it.isNotEmpty() }
    }

    private const val ICARUS_EXECUTABLE_NAME = "icarus"
    private const val STREAM_READ_BUFFER_SIZE = 4096
    private const val PATH_ENVIRONMENT_VARIABLE = "PATH"
    private const val SHELL_ENVIRONMENT_VARIABLE = "SHELL"
    private const val COMMAND_ALREADY_RUNNING_MESSAGE = "An instance of Icarus Builder is already running."
    private const val WORKSPACE_CONFIG_FILE_NAME = "icarus.cfg"
    private const val DEFAULT_LOGIN_SHELL = "/bin/zsh"
    private const val SHELL_PATH_PROBE_TIMEOUT_SECONDS = 3L
    private const val INTERACTIVE_PATH_PROBE_PREFIX = "__ICARUS_PATH_START__"
    private const val INTERACTIVE_PATH_PROBE_SUFFIX = "__ICARUS_PATH_END__"
    private const val INTERACTIVE_PATH_PROBE_COMMAND = "printf '__ICARUS_PATH_START__%s__ICARUS_PATH_END__' \"\$PATH\""
    private val ANSI_ESCAPE_SEQUENCE_REGEX = Regex("\\u001B\\[[;\\d]*m")
}
