package com.yandex.yatagan.testing.tests

import com.yandex.yatagan.generated.CompiledApiClasspath
import com.yandex.yatagan.generated.IntelliJTestDriverClasspath
import com.yandex.yatagan.intellij.testing.rmi.IntelliJTestService
import com.yandex.yatagan.testing.source_set.SourceSet
import org.junit.Assert
import java.io.File
import java.rmi.registry.LocateRegistry
import kotlin.io.path.createTempDirectory

class IntelliJTestLauncher : CompileTestDriverBase() {
    override val backendUnderTest: Backend
        get() = Backend.IntelliJ

    override fun generatedFilesSubDir(): String? {
        return null
    }

    override fun givenPrecompiledModule(sources: SourceSet) {
        // TODO: Fallback to heavy (multi-module) test?
        includeFromSourceSet(sources)
    }

    override fun compileRunAndValidate() {
        val goldenResourcePath = "golden/${testNameRule.testClassSimpleName}/${testNameRule.testMethodName}.golden.txt"
        val workingDir = createTempDirectory(prefix = "yct-${testNameRule.testMethodName}").toFile()

        for (sourceFile in sourceFiles) {
            workingDir.resolve(sourceFile.relativePath)
                .also { it.parentFile.mkdirs() }
                .writeText(sourceFile.contents)
        }

        val output = runTest(
            projectDirectory = workingDir.absolutePath,
        )

        val goldenOutput = javaClass.getResourceAsStream("/$goldenResourcePath")?.bufferedReader()?.readText() ?: ""
        val strippedLog = normalizeMessages(output)

        Assert.assertEquals(goldenOutput, strippedLog)
    }

    private companion object : IntelliJTestService {
        private val commandLine = File(System.getProperty("com.yandex.yatagan.intellij-test-driver-command-line"))
            .readLines().toTypedArray()
        private val jdk8Path = System.getProperty("com.yandex.yatagan.jdk8").also {
            check(File(it).isDirectory) { "Invalid jdk8 path: $it" }
        }

        private var process: Process? = null

        init {
            process = ProcessBuilder()
                .command(
                    *commandLine,
                    "--class-path",
                    IntelliJTestDriverClasspath,
                    "com.yandex.yatagan.intellij.testing.Main",
                    CompiledApiClasspath,
                    jdk8Path,
                )
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        }

        override fun runTest(projectDirectory: String): String {
            ensureDaemonIsAlive()
            val service = connectToService()
            return service.runTest(projectDirectory)
        }

        private fun connectToService(): IntelliJTestService {
            val registry = withAttempts(5, onError = { Thread.sleep(500L) }) {
                LocateRegistry.getRegistry("localhost", IntelliJTestService.RMI_PORT)
            }

            val service = withAttempts(5, onError = { Thread.sleep(500L) }) {
                registry.lookup(IntelliJTestService::class.qualifiedName)
            } as IntelliJTestService

            return service
        }

        private fun ensureDaemonIsAlive() {
            if (process?.isAlive == true) {
                return
            }

            println("Launching IntelliJ Test Daemon")
            process = ProcessBuilder()
                .command(
                    *commandLine,
                    "--class-path",
                    IntelliJTestDriverClasspath,
                    "com.yandex.yatagan.intellij.testing.Main",
                    CompiledApiClasspath,
                    jdk8Path,
                    // System properties
                )
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        }

        private inline fun <R> withAttempts(count: Int, onError: () -> Unit, block: () -> R): R {
            var attempt = 1
            while (true) {
                try {
                    return block()
                } catch (e: Throwable) {
                    if (attempt++ >= count) {
                        throw e
                    }
                    onError()
                }
            }
        }
    }
}