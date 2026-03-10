package org.carlogtt.plugins.icarus

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object IcarusEnvironment {

    data class ExecutableHomeInfo(
        val expectedExecutablePath: Path?,
        val executableAvailable: Boolean,
    )

    fun executableHomeInfo(): ExecutableHomeInfo {
        val homeDirectory = resolveHomeDirectory()
        val expectedExecutablePath = expectedIcarusExecutablePath(homeDirectory)
        val executableAvailable = expectedExecutablePath?.let(::isExecutableFile) ?: false

        return ExecutableHomeInfo(expectedExecutablePath, executableAvailable)
    }

    fun resolveIcarusExecutablePath(): Path? {
        val expectedPath = expectedIcarusExecutablePath() ?: return null
        return if (isExecutableFile(expectedPath)) expectedPath else null
    }

    fun expectedIcarusExecutablePath(): Path? {
        return expectedIcarusExecutablePath(resolveHomeDirectory())
    }

    private fun expectedIcarusExecutablePath(homeDirectory: String?): Path? {
        if (homeDirectory.isNullOrBlank()) {
            return null
        }

        return try {
            Path.of(homeDirectory, ".icarus", "bin", ICARUS_EXECUTABLE_NAME).toAbsolutePath().normalize()
        }
        catch (_: InvalidPathException) {
            null
        }
    }

    private fun resolveHomeDirectory(): String? {
        return System.getProperty("user.home")
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("HOME")?.takeIf { it.isNotBlank() }
    }

    private fun isExecutableFile(path: Path): Boolean {
        return Files.isRegularFile(path) && Files.isExecutable(path)
    }

    private const val ICARUS_EXECUTABLE_NAME = "icarus"
}
