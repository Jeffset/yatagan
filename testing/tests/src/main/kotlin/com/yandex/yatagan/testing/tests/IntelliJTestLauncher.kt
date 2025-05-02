package com.yandex.yatagan.testing.tests

import com.yandex.yatagan.generated.CurrentClasspath
import com.yandex.yatagan.generated.IntelliJ
import com.yandex.yatagan.intellij.testing.rmi.IntelliJTestService
import com.yandex.yatagan.testing.source_set.SourceSet
import org.junit.Assert
import java.io.File
import java.rmi.registry.LocateRegistry
import kotlin.io.path.createTempDirectory


private val ADD_OPENS = arrayOf(
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.ref=ALL-UNNAMED",
    "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens=java.base/java.net=ALL-UNNAMED",
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.nio.charset=ALL-UNNAMED",
    "--add-opens=java.base/java.text=ALL-UNNAMED",
    "--add-opens=java.base/java.time=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
    "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED",
    "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
    "--add-opens=java.base/sun.security.util=ALL-UNNAMED",
    "--add-opens=java.base/sun.net.dns=ALL-UNNAMED",
    "--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.image=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.text=ALL-UNNAMED",
    "--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt.image=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
    "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
    "--add-opens=java.desktop/com.sun.java.swing=ALL-UNNAMED",
    "--add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED",
    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
    "--add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED",
)

class IntelliJTestLauncher : CompileTestDriverBase() {
    override val backendUnderTest: Backend
        get() = Backend.IntelliJ

    override fun generatedFilesSubDir(): String? {
        return null
    }

    override val checkGoldenOutput: Boolean
        get() = false

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
            .readLines().filter { it.isNotBlank() } .toTypedArray()
        private val jdk8Path = System.getProperty("com.yandex.yatagan.jdk8").also {
            check(File(it).isDirectory) { "Invalid jdk8 path: $it" }
        }

        private val process: Process = ProcessBuilder()
            .command(
                *commandLine,
                *ADD_OPENS,
//                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005",
                "--class-path",
                IntelliJ.TestDriverClasspath,
                "com.yandex.yatagan.intellij.testing.Main",
                CurrentClasspath.ApiCompiled,
                jdk8Path,
            )
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        init {
            Runtime.getRuntime().addShutdownHook(Thread {
                process.destroy()
            })
        }

        override fun runTest(projectDirectory: String): String {
            val service = connectToService()
            return service.runTest(projectDirectory)
        }

        private fun connectToService(): IntelliJTestService {
            val registry = withAttempts(10, onError = { Thread.sleep(500L) }) {
                LocateRegistry.getRegistry("localhost", IntelliJTestService.RMI_PORT)
            }

            val service = withAttempts(10, onError = { Thread.sleep(500L) }) {
                registry.lookup(IntelliJTestService::class.qualifiedName)
            } as IntelliJTestService

            return service
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