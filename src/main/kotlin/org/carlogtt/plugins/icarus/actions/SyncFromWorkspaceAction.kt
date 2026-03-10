package org.carlogtt.plugins.icarus.actions

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import org.carlogtt.plugins.icarus.toolwindow.IcarusOutputService
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

class SyncFromWorkspaceAction : AnAction() {

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabled = project != null && IcarusActionSupport.resolveDetectedWorkspaceRoot(project) != null
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
                IcarusActionSupport.notify(project, NotificationType.ERROR, "Workspace not found.")
                return
            }

        object : Task.Backgroundable(project, "Sync From Workspace", false) {
            private var syncResult: SyncExecutionResult = SyncExecutionResult.Error("Sync did not run.")

            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running Icarus Builder"
                DumbService.getInstance(project).suspendIndexingAndRun("Icarus workspace sync", Runnable {
                    syncResult = runSyncWorkflow(project, workspaceRoot)
                })
            }

            override fun onSuccess() {
                when (val result = syncResult) {
                    is SyncExecutionResult.Error -> {
                        Unit
                    }

                    is SyncExecutionResult.Success -> {
                        Unit
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

    private fun runSyncWorkflow(project: Project, workspaceRoot: String): SyncExecutionResult {
        val startedAtMillis = System.currentTimeMillis()

        val outputService = project.service<IcarusOutputService>()
        val outputSession = IcarusActionSupport.createRunSession(
            project = project,
            header = SYNC_RUN_HEADER,
            tabTitleBaseOverride = SYNC_TAB_TITLE,
        ) ?: return SyncExecutionResult.Error("Icarus output widget is unavailable.")

        val workspaceName = resolveStepValue(
            project = project,
            workspaceRoot = workspaceRoot,
            outputService = outputService,
            outputSession = outputSession,
            stepNumber = 1,
            pathKey = "workspace.name",
        ) ?: return SyncExecutionResult.Error("Step 1 failed.")

        if (resolveStepValue(
            project = project,
            workspaceRoot = workspaceRoot,
            outputService = outputService,
            outputSession = outputSession,
            stepNumber = 2,
            pathKey = "devrun_excluderoot.runtimefarm",
        ) == null) {
            return SyncExecutionResult.Error("Step 2 failed.")
        }

        val pythonHomePath = resolveStepValue(
            project = project,
            workspaceRoot = workspaceRoot,
            outputService = outputService,
            outputSession = outputSession,
            stepNumber = 3,
            pathKey = "devrun_excluderoot.pythonhome",
        ) ?: return SyncExecutionResult.Error("Step 3 failed.")

        val pythonPath = resolveStepValue(
            project = project,
            workspaceRoot = workspaceRoot,
            outputService = outputService,
            outputSession = outputSession,
            stepNumber = 4,
            pathKey = "devrun_excluderoot.pythonpath",
        ) ?: return SyncExecutionResult.Error("Step 4 failed.")

        val resolvedWorkspaceRoot = resolveStepValue(
            project = project,
            workspaceRoot = workspaceRoot,
            outputService = outputService,
            outputSession = outputSession,
            stepNumber = 5,
            pathKey = "workspace.root",
        ) ?: return SyncExecutionResult.Error("Step 5 failed.")

        val workspaceSrcRoot = resolveStepValue(
            project = project,
            workspaceRoot = workspaceRoot,
            outputService = outputService,
            outputSession = outputSession,
            stepNumber = 6,
            pathKey = "workspace.src-root",
        ) ?: return SyncExecutionResult.Error("Step 6 failed.")

        val workspaceBuildRoot = resolveStepValue(
            project = project,
            workspaceRoot = workspaceRoot,
            outputService = outputService,
            outputSession = outputSession,
            stepNumber = 7,
            pathKey = "workspace.build-root",
        ) ?: return SyncExecutionResult.Error("Step 7 failed.")

        outputService.appendSystem(
            outputSession,
            "Step 8: Configure IDE Workspace\n",
        )

        val rootsResult = runOnEdtAndWait {
            configureWorkspaceRoots(
                project = project,
                workspaceRootPath = resolvedWorkspaceRoot,
                workspaceBuildRootPath = workspaceBuildRoot,
                workspaceSrcRootPath = workspaceSrcRoot,
            )
        }

        when (rootsResult) {
            is RootsConfigurationResult.Error -> {
                outputService.appendStdErr(outputSession, "Step 8 failed: ${rootsResult.message}\n")
                return SyncExecutionResult.Error(rootsResult.message)
            }

            RootsConfigurationResult.Success -> {
                outputService.appendStdOut(
                    outputSession,
                    buildWorkspaceConfigurationOutput(
                        workspaceRootPath = resolvedWorkspaceRoot,
                        workspaceBuildRootPath = workspaceBuildRoot,
                        workspaceSrcRootPath = workspaceSrcRoot,
                    ),
                )
            }
        }

        outputService.appendSystem(
            outputSession,
            "Step 9: Configure IDE Python SDK\n",
        )

        val pythonVersionInfo = resolvePythonVersionsFromPythonHome(pythonHomePath)
            ?: return SyncExecutionResult.Error("Step 9 failed: Could not resolve Python versions from Step 3 path basename.")

        val interpreterPath = buildInterpreterPath(pythonHomePath)
            ?: return SyncExecutionResult.Error("Step 9 failed: Invalid python home path from Step 3.")

        val interpreterLibPath = pythonPath

        val sdkName = "Py${pythonVersionInfo.pyFullVersion} ($workspaceName)"
        val sdkResult = runOnEdtAndWait {
            configureProjectInterpreter(project, interpreterPath, interpreterLibPath, sdkName)
        }
        when (sdkResult) {
            is SdkConfigurationResult.Error -> {
                outputService.appendStdErr(outputSession, "Step 9 failed: ${sdkResult.message}\n")
                return SyncExecutionResult.Error(sdkResult.message)
            }

            is SdkConfigurationResult.Success -> {
                outputService.appendStdOut(outputSession, "$sdkName\n\n")
            }
        }

        val elapsedSeconds = (System.currentTimeMillis() - startedAtMillis) / 1000.0
        outputService.appendSystem(
            outputSession,
            "Workspace sync completed in ${"%.1f".format(Locale.US, elapsedSeconds)} secs\n",
        )

        return SyncExecutionResult.Success(sdkName)
    }

    private fun resolveStepValue(
        project: Project,
        workspaceRoot: String,
        outputService: IcarusOutputService,
        outputSession: IcarusOutputService.OutputSession,
        stepNumber: Int,
        pathKey: String,
    ): String? {
        val command = IcarusActionSupport.buildIcarusBuilderCommand(listOf("path", pathKey))
        val commandLine = IcarusActionSupport.toCommandLine(command)
        outputService.appendSystem(outputSession, "Step $stepNumber: $commandLine\n")

        val commandResult = IcarusActionSupport.runCommandInSession(
            project = project,
            workspaceRoot = workspaceRoot,
            command = command,
            outputSession = outputSession,
            includeCommandHeader = false,
            showStderrInWidget = false,
        )

        when (commandResult) {
            is IcarusActionSupport.CommandRunResult.Error -> {
                outputService.appendStdErr(outputSession, "Step $stepNumber failed: ${commandResult.message}\n")
                outputService.appendSystem(outputSession, "\n")
                return null
            }

            is IcarusActionSupport.CommandRunResult.Success -> {
                val failureMessage = IcarusActionSupport.commandFailureMessage(commandResult)
                if (failureMessage != null) {
                    outputService.appendStdErr(outputSession, "Step $stepNumber failed: $failureMessage\n")
                    outputService.appendSystem(outputSession, "\n")
                    return null
                }

                val resolvedValue = extractResolvedPathValue(commandResult.stdout, pathKey)
                if (resolvedValue == null) {
                    outputService.appendStdErr(outputSession, "Step $stepNumber failed: No value returned for $pathKey\n")
                    outputService.appendSystem(outputSession, "\n")
                    return null
                }

                outputService.appendSystem(outputSession, "\n")
                return resolvedValue
            }
        }
    }

    private fun configureProjectInterpreter(
        project: Project,
        interpreterPath: String,
        interpreterLibPath: String,
        sdkName: String,
    ): SdkConfigurationResult {
        val interpreterFile = safePath(interpreterPath)
            ?: return SdkConfigurationResult.Error("Interpreter path is invalid: $interpreterPath")
        val interpreterLibDirectory = safePath(interpreterLibPath)
            ?: return SdkConfigurationResult.Error("Interpreter lib path is invalid: $interpreterLibPath")

        if (!Files.isRegularFile(interpreterFile)) {
            return SdkConfigurationResult.Error("Interpreter file is missing: $interpreterPath")
        }
        if (!Files.isExecutable(interpreterFile)) {
            return SdkConfigurationResult.Error("Interpreter file is not executable: $interpreterPath")
        }
        if (!Files.isDirectory(interpreterLibDirectory)) {
            return SdkConfigurationResult.Error("Interpreter lib path is missing: $interpreterLibPath")
        }

        val pythonSdkType = SdkType.findByName(PYTHON_SDK_NAME)
            ?: SdkType.getAllTypeList().firstOrNull { it.javaClass.name == PYTHON_SDK_CLASS }
            ?: return SdkConfigurationResult.Error("Python SDK type is unavailable. Install and enable the Python plugin.")

        val existingSdks = ProjectJdkTable.getInstance().allJdks
        val sdk = SdkConfigurationUtil.findByPath(pythonSdkType, existingSdks, interpreterPath)
            ?: SdkConfigurationUtil.createAndAddSDK(interpreterPath, pythonSdkType)
            ?: return SdkConfigurationResult.Error("Could not create a Python SDK for path: $interpreterPath")

        val projectRootManager = ProjectRootManager.getInstance(project)
        val wasAlreadyProjectInterpreter = isSameSdk(projectRootManager.projectSdk, sdk)

        WriteAction.run<Throwable> {
            updateSdkNameAndLibPath(sdk, sdkName, interpreterLibPath)
            if (!wasAlreadyProjectInterpreter) {
                projectRootManager.projectSdk = sdk
            }
            ModuleManager.getInstance(project).modules.forEach { module ->
                ModuleRootModificationUtil.setModuleSdk(module, sdk)
            }
        }

        if (!isSameSdk(ProjectRootManager.getInstance(project).projectSdk, sdk)) {
            return SdkConfigurationResult.Error("Resolved Python SDK exists but is not set as project interpreter.")
        }

        return SdkConfigurationResult.Success(sdk)
    }

    private fun isSameSdk(current: Sdk?, target: Sdk): Boolean {
        if (current == null) {
            return false
        }
        if (current == target) {
            return true
        }

        val currentPath = current.homePath
        val targetPath = target.homePath
        if (currentPath != null && targetPath != null) {
            val normalizedCurrent = safePath(currentPath)?.toString() ?: currentPath
            val normalizedTarget = safePath(targetPath)?.toString() ?: targetPath
            if (normalizedCurrent == normalizedTarget) {
                return true
            }
        }

        return current.name == target.name && current.sdkType.name == target.sdkType.name
    }

    private fun updateSdkNameAndLibPath(sdk: Sdk, desiredName: String, interpreterLibPath: String) {
        val sdkModificator = sdk.sdkModificator
        val interpreterLibUrl = VfsUtilCore.pathToUrl(interpreterLibPath)
        val legacyInterpreterLibPath = safePath(interpreterLibPath)?.parent?.toString()
        val legacyInterpreterLibUrl = legacyInterpreterLibPath?.let { path -> VfsUtilCore.pathToUrl(path) }

        if (sdk.name != desiredName) {
            sdkModificator.name = desiredName
        }

        addInterpreterPathToPythonAdditionalData(
            sdk = sdk,
            sdkModificator = sdkModificator,
            interpreterLibPath = interpreterLibPath,
            legacyInterpreterLibPath = legacyInterpreterLibPath,
        )

        if (legacyInterpreterLibUrl != null && legacyInterpreterLibUrl != interpreterLibUrl) {
            val legacyRoots = sdkModificator.getRoots(OrderRootType.CLASSES)
                .filter { root -> root.url == legacyInterpreterLibUrl }
            legacyRoots.forEach { legacyRoot ->
                sdkModificator.removeRoot(legacyRoot, OrderRootType.CLASSES)
            }
        }

        val hasLibPath = sdkModificator.getRoots(OrderRootType.CLASSES)
            .any { root -> root.url == interpreterLibUrl }
        if (!hasLibPath) {
            sdkModificator.addRoot(interpreterLibUrl, OrderRootType.CLASSES)
        }

        sdkModificator.commitChanges()
    }

    private fun addInterpreterPathToPythonAdditionalData(
        sdk: Sdk,
        sdkModificator: SdkModificator,
        interpreterLibPath: String,
        legacyInterpreterLibPath: String?,
    ) {
        val interpreterLibVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(interpreterLibPath) ?: return
        val normalizedInterpreterLibPath = safePath(interpreterLibPath)?.toString() ?: interpreterLibPath
        val normalizedLegacyPath = legacyInterpreterLibPath?.let { path -> safePath(path)?.toString() ?: path }

        val additionalData = resolvePythonSdkAdditionalData(sdk, sdkModificator) ?: return
        if (additionalData.javaClass.name != PYTHON_SDK_ADDITIONAL_DATA_CLASS) {
            return
        }

        val mergedAddedPaths = linkedSetOf<VirtualFile>()
        try {
            val currentAddedPaths = additionalData.javaClass
                .getMethod("getAddedPathFiles")
                .invoke(additionalData) as? Set<*>
            currentAddedPaths
                ?.filterIsInstance<VirtualFile>()
                ?.filter { file ->
                    val normalizedCurrentPath = safePath(file.path)?.toString() ?: file.path
                    normalizedCurrentPath != normalizedLegacyPath && normalizedCurrentPath != normalizedInterpreterLibPath
                }
                ?.forEach(mergedAddedPaths::add)
        }
        catch (_: Exception) {
        }

        mergedAddedPaths.add(interpreterLibVirtualFile)

        try {
            additionalData.javaClass
                .getMethod("setAddedPathsFromVirtualFiles", Set::class.java)
                .invoke(additionalData, mergedAddedPaths)

            val sdkAdditionalData = additionalData as? SdkAdditionalData
            if (sdkAdditionalData != null) {
                sdkModificator.sdkAdditionalData = sdkAdditionalData
            }
        }
        catch (_: Exception) {
        }
    }

    private fun resolvePythonSdkAdditionalData(sdk: Sdk, sdkModificator: SdkModificator): Any? {
        val existingAdditionalData = sdkModificator.sdkAdditionalData
        if (existingAdditionalData != null) {
            return existingAdditionalData
        }

        return try {
            val pySdkCoreToolsClass = Class.forName(PYTHON_SDK_CORE_TOOLS_CLASS)
            val getOrCreateAdditionalDataMethod = pySdkCoreToolsClass.getMethod("getOrCreateAdditionalData", Sdk::class.java)
            getOrCreateAdditionalDataMethod.invoke(null, sdk)
        }
        catch (_: Exception) {
            null
        }
    }

    private fun configureWorkspaceRoots(
        project: Project,
        workspaceRootPath: String,
        workspaceBuildRootPath: String,
        workspaceSrcRootPath: String,
    ): RootsConfigurationResult {
        val workspaceRoot = safePath(workspaceRootPath)
            ?: return RootsConfigurationResult.Error("workspace.root is invalid: $workspaceRootPath")

        val workspaceSrcRoot = safePath(workspaceSrcRootPath)
            ?: return RootsConfigurationResult.Error("workspace.src-root is invalid: $workspaceSrcRootPath")

        val workspaceBuildRoot = safePath(workspaceBuildRootPath)
            ?: return RootsConfigurationResult.Error("workspace.build-root is invalid: $workspaceBuildRootPath")

        val hiddenIcarusFolder = workspaceRoot.resolve(".icarus")
        val candidateTestFolders = listOf(workspaceRoot.resolve("test"), workspaceRoot.resolve("tests"))
            .filter { path -> Files.isDirectory(path) }

        val modules = ModuleManager.getInstance(project).modules
        if (modules.isEmpty()) {
            return RootsConfigurationResult.Error("No IDE modules found to configure workspace roots.")
        }

        WriteAction.run<Throwable> {
            modules.forEach { module ->
                ModuleRootModificationUtil.updateModel(module) { model ->
                    val contentEntry = ensureContentEntry(
                        model = model,
                        workspaceRoot = workspaceRoot,
                        workspaceSrcRoot = workspaceSrcRoot,
                        workspaceBuildRoot = workspaceBuildRoot,
                    )
                    val mappedWorkspaceSrcRoot = mapPathToContentEntry(contentEntry, workspaceRoot, workspaceSrcRoot)
                    val mappedWorkspaceBuildRoot = mapPathToContentEntry(contentEntry, workspaceRoot, workspaceBuildRoot)
                    val mappedHiddenIcarusFolder = mapPathToContentEntry(contentEntry, workspaceRoot, hiddenIcarusFolder)

                    ensureSourceFolder(contentEntry, mappedWorkspaceSrcRoot, isTest = false)
                    ensureExcludeFolder(contentEntry, mappedWorkspaceBuildRoot)
                    ensureExcludeFolder(contentEntry, mappedHiddenIcarusFolder)
                    candidateTestFolders.forEach { testFolder ->
                        val mappedTestFolder = mapPathToContentEntry(contentEntry, workspaceRoot, testFolder)
                        ensureSourceFolder(contentEntry, mappedTestFolder, isTest = true)
                    }
                }
            }
        }

        return RootsConfigurationResult.Success
    }

    private fun ensureContentEntry(
        model: ModifiableRootModel,
        workspaceRoot: Path,
        workspaceSrcRoot: Path,
        workspaceBuildRoot: Path,
    ): ContentEntry {
        val normalizedWorkspaceRoot = normalizeForComparison(workspaceRoot)
        val normalizedWorkspaceSrcRoot = normalizeForComparison(workspaceSrcRoot)
        val normalizedWorkspaceBuildRoot = normalizeForComparison(workspaceBuildRoot)

        val matchingEntry = model.contentEntries.firstOrNull { contentEntry ->
            val entryPath = contentEntryPath(contentEntry) ?: return@firstOrNull false
            val normalizedEntryPath = normalizeForComparison(entryPath)

            normalizedEntryPath == normalizedWorkspaceRoot ||
                normalizedWorkspaceSrcRoot.startsWith(normalizedEntryPath) ||
                normalizedWorkspaceBuildRoot.startsWith(normalizedEntryPath)
        }
        if (matchingEntry != null) {
            return matchingEntry
        }

        val rootUrl = VfsUtilCore.pathToUrl(workspaceRoot.toString())
        return model.contentEntries.firstOrNull { entry -> entry.url == rootUrl } ?: model.addContentEntry(rootUrl)
    }

    private fun contentEntryPath(contentEntry: ContentEntry): Path? {
        val entryVirtualFile = contentEntry.file
        val entryPath = entryVirtualFile?.path ?: VfsUtilCore.urlToPath(contentEntry.url)
        return safePath(entryPath)
    }

    private fun normalizeForComparison(path: Path): Path {
        return try {
            path.toRealPath()
        }
        catch (_: Exception) {
            path.toAbsolutePath().normalize()
        }
    }

    private fun mapPathToContentEntry(contentEntry: ContentEntry, workspaceRoot: Path, targetPath: Path): Path {
        val contentEntryRoot = contentEntryPath(contentEntry) ?: return targetPath
        val normalizedWorkspaceRoot = normalizeForComparison(workspaceRoot)
        val normalizedTargetPath = normalizeForComparison(targetPath)

        if (!normalizedTargetPath.startsWith(normalizedWorkspaceRoot)) {
            return targetPath
        }

        return try {
            val relativeTarget = normalizedWorkspaceRoot.relativize(normalizedTargetPath)
            contentEntryRoot.resolve(relativeTarget).toAbsolutePath().normalize()
        }
        catch (_: IllegalArgumentException) {
            targetPath
        }
    }

    private fun ensureSourceFolder(contentEntry: ContentEntry, folderPath: Path, isTest: Boolean) {
        val folderUrl = VfsUtilCore.pathToUrl(folderPath.toString())
        val duplicate = contentEntry.sourceFolders.firstOrNull { sourceFolder ->
            sourceFolder.url == folderUrl && sourceFolder.isTestSource == isTest
        }
        if (duplicate == null) {
            contentEntry.addSourceFolder(folderUrl, isTest)
        }
    }

    private fun ensureExcludeFolder(contentEntry: ContentEntry, folderPath: Path) {
        val folderUrl = VfsUtilCore.pathToUrl(folderPath.toString())
        if (!contentEntry.excludeFolderUrls.contains(folderUrl)) {
            contentEntry.addExcludeFolder(folderUrl)
        }
    }

    private fun buildInterpreterPath(pythonHomePath: String): String? {
        val pythonHome = safePath(pythonHomePath) ?: return null
        val interpreterPath = pythonHome.resolve("bin").resolve("python3")
        return interpreterPath.toString()
    }

    private fun buildWorkspaceConfigurationOutput(
        workspaceRootPath: String,
        workspaceBuildRootPath: String,
        workspaceSrcRootPath: String,
    ): String {
        val lines = mutableListOf<String>()
        lines += "$workspaceSrcRootPath -> Source"
        lines += "$workspaceBuildRootPath -> Exclude"

        val workspaceRoot = safePath(workspaceRootPath)
        if (workspaceRoot != null) {
            lines += "${workspaceRoot.resolve(".icarus")} -> Exclude"

            val testFolders = listOf(workspaceRoot.resolve("test"), workspaceRoot.resolve("tests"))
                .filter { path -> Files.isDirectory(path) }
            testFolders.forEach { testFolder ->
                lines += "$testFolder -> Test"
            }
        }
        else {
            lines += "$workspaceRootPath/.icarus -> Exclude"
        }

        return lines.joinToString(separator = "\n", postfix = "\n\n")
    }

    private fun resolvePythonVersionsFromPythonHome(pythonHomePath: String): PythonVersionInfo? {
        val pythonHome = safePath(pythonHomePath) ?: return null
        val basename = pythonHome.fileName?.toString()?.trim().orEmpty()
        if (basename.isEmpty()) {
            return null
        }

        val pyFullVersion = PYTHON_HOME_BASENAME_VERSION_REGEX.matchEntire(basename)?.groupValues?.get(1)
            ?: return null
        val pyVersion = PYTHON_VERSION_PREFIX_REGEX.matchEntire(pyFullVersion)?.groupValues?.get(1)
            ?: return null

        return PythonVersionInfo(pyVersion = pyVersion, pyFullVersion = pyFullVersion)
    }

    private fun extractResolvedPathValue(stdout: String, pathKey: String): String? {
        return if (COLON_DELIMITED_PATH_KEYS.contains(pathKey)) {
            extractFirstColonDelimitedToken(stdout)
        }
        else {
            extractFirstLineToken(stdout)
        }
    }

    private fun extractFirstColonDelimitedToken(stdout: String): String? {
        return stdout
            .lineSequence()
            .flatMap { line -> line.split(':').asSequence() }
            .map { token -> token.trim() }
            .firstOrNull { token -> token.isNotEmpty() }
    }

    private fun extractFirstLineToken(stdout: String): String? {
        return stdout
            .lineSequence()
            .map { line -> line.trim() }
            .firstOrNull { token -> token.isNotEmpty() }
    }

    private fun safePath(value: String): Path? {
        return try {
            Path.of(value).toAbsolutePath().normalize()
        }
        catch (_: InvalidPathException) {
            null
        }
    }

    private fun <T> runOnEdtAndWait(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }

        var result: T? = null
        application.invokeAndWait {
            result = action()
        }

        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private sealed interface SyncExecutionResult {
        data class Success(val sdkName: String) : SyncExecutionResult
        data class Error(val message: String) : SyncExecutionResult
    }

    private data class PythonVersionInfo(
        val pyVersion: String,
        val pyFullVersion: String,
    )

    private sealed interface SdkConfigurationResult {
        data class Success(val sdk: Sdk) : SdkConfigurationResult
        data class Error(val message: String) : SdkConfigurationResult
    }

    private sealed interface RootsConfigurationResult {
        data object Success : RootsConfigurationResult
        data class Error(val message: String) : RootsConfigurationResult
    }

    companion object {
        private val PYTHON_HOME_BASENAME_VERSION_REGEX = Regex("(\\d+\\.\\d+\\.\\d+)")
        private val PYTHON_VERSION_PREFIX_REGEX = Regex("(\\d+\\.\\d+)\\.\\d+")
        private const val PYTHON_SDK_NAME = "Python SDK"
        private const val PYTHON_SDK_CLASS = "com.jetbrains.python.sdk.PythonSdkType"
        private const val PYTHON_SDK_ADDITIONAL_DATA_CLASS = "com.jetbrains.python.sdk.PythonSdkAdditionalData"
        private const val PYTHON_SDK_CORE_TOOLS_CLASS = "com.jetbrains.python.sdk.PySdkCoreToolsKt"
        private const val SYNC_RUN_HEADER = "sync from workspace"
        private const val SYNC_TAB_TITLE = "SyncWs"
        private val COLON_DELIMITED_PATH_KEYS = setOf(
            "devrun_excluderoot.pythonhome",
            "devrun_excluderoot.pythonpath",
        )
    }
}
