package org.jetbrains.plugins.icarus.actions

import com.intellij.ide.SaveAndSyncHandler
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
import org.jetbrains.plugins.icarus.IcarusEnvironment
import org.jetbrains.plugins.icarus.toolwindow.IcarusOutputService
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
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

    fun buildIcarusBuilderCommand(arguments: List<String>): List<String> {
        return ICARUS_BUILDER_BASE_COMMAND + arguments
    }

    fun toCommandLine(command: List<String>): String {
        return command.joinToString(" ")
    }

    fun runCommandInWidget(project: Project, workspaceRoot: String, command: List<String>): CommandRunResult {
        saveAllOpenDocuments(project)

        val outputService = project.service<IcarusOutputService>()
        val commandLine = toCommandLine(command)
        val outputSession = outputService.startRun(commandLine)
            ?: return CommandRunResult.Error("Icarus output widget is unavailable.")
        setRunningTabTitle(project, outputSession.tabTitle)
        outputService.clearHomeActionStatus()

        try {
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
                augmentExecutionPath(processBuilder.environment())
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
                        outputService.appendProcessChunk(outputSession, chunk, ProcessOutputTypes.STDERR, ansiEscapeDecoder)
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

            outputService.appendSystem(outputSession, "\n[exit code $exitCode]\n")

            return CommandRunResult.Success(
                commandLine = commandLine,
                stdout = stdout.toString(),
                stderr = stderr.toString(),
                exitCode = exitCode,
            )
        }
        finally {
            outputService.clearHomeActionStatus()
        }
    }

    fun commandFailureMessage(result: CommandRunResult.Success): String? {
        if (result.exitCode == 0) {
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

    private fun saveAllOpenDocuments(project: Project) {
        val application = ApplicationManager.getApplication()
        val saveAction = {
            FileDocumentManager.getInstance().saveAllDocuments()
            SaveAndSyncHandler.getInstance().scheduleSave(
                SaveAndSyncHandler.SaveTask(project = project, forceSavingAllSettings = true),
                true,
            )
        }

        if (application.isDispatchThread) {
            saveAction()
            return
        }

        application.invokeAndWait {
            saveAction()
        }
    }

    private fun augmentExecutionPath(environment: MutableMap<String, String>) {
        val currentEntries = environment[PATH_ENVIRONMENT_VARIABLE]
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val preferredEntries = preferredPathEntries()
            .filter { entry ->
                try {
                    Files.isDirectory(Path.of(entry))
                }
                catch (_: InvalidPathException) {
                    false
                }
            }

        val mergedEntries = linkedSetOf<String>()
        preferredEntries.forEach(mergedEntries::add)
        currentEntries.forEach(mergedEntries::add)

        environment[PATH_ENVIRONMENT_VARIABLE] = mergedEntries.joinToString(File.pathSeparator)
    }

    private fun preferredPathEntries(): List<String> {
        val homeDirectory = IcarusEnvironment.homeDirectory()
        val userEntries = if (homeDirectory.isNullOrBlank()) {
            emptyList()
        }
        else {
            listOf(
                "$homeDirectory/.local/bin",
                "$homeDirectory/.local/sbin",
                "$homeDirectory/bin",
                "$homeDirectory/sbin",
            )
        }

        return userEntries + listOf(
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin",
            "/home/linuxbrew/.linuxbrew/bin",
            "/home/linuxbrew/.linuxbrew/sbin",
            "/usr/local/bin",
            "/usr/local/sbin",
            "/usr/bin",
            "/usr/sbin",
            "/bin",
            "/sbin",
        )
    }

    private const val ICARUS_EXECUTABLE_NAME = "icarus"
    private const val STREAM_READ_BUFFER_SIZE = 4096
    private const val PATH_ENVIRONMENT_VARIABLE = "PATH"
    private const val COMMAND_ALREADY_RUNNING_MESSAGE = "An instance of Icarus Builder is already running."
    private val ANSI_ESCAPE_SEQUENCE_REGEX = Regex("\\u001B\\[[;\\d]*m")
}
