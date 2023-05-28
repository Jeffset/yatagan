package com.yandex.yatagan.testing.tests

import com.yandex.yatagan.generated.CompiledApiClasspath
import com.yandex.yatagan.generated.IntelliJTestDriverClasspath
import com.yandex.yatagan.testing.source_set.SourceSet
import org.junit.Assert
import java.io.File
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

        val javaBinary = File(System.getProperty("java.home"))
            .resolve("bin/java")

        for (sourceFile in sourceFiles) {
            workingDir.resolve(sourceFile.relativePath)
                .also { it.parentFile.mkdirs() }
                .writeText(sourceFile.contents)
        }
        // TODO: Implement a daemon behavior with the test process to be able to reuse it to greatly speed up the tests.
        val process = ProcessBuilder()
            .command(
                javaBinary.absolutePath,

                // TODO: Do not hardcode IntelliJ related arguments, provide them via gradle task (from Test) instead.
                // System properties
                "-Didea.force.use.core.classloader=true",
                "-Didea.use.core.classloader.for=com.yandex.yatagan.intellij",
                "-Didea.use.core.classloader.for.plugin.path=true",
                "-Djbr.catch.SIGABRT=true",
                "-Djdk.attach.allowAttachSelf=true",
                "-Djdk.http.auth.tunneling.disabledSchemes=\"\"",
                "-Djdk.module.illegalAccess.silent=true",
                "-Dkotlinx.coroutines.debug=off",
                "-Dsun.io.useCanonCaches=false",
                "-Dsun.java2d.metal=true",
                "-Dsun.tools.attach.tmp.only=true",

                // Jvm Options
                "-XX:ReservedCodeCacheSize=512m",
                "-XX:+UseG1GC",
                "-XX:SoftRefLRUPolicyMSPerMB=50",
                "-XX:CICompilerCount=2",
                "-XX:+HeapDumpOnOutOfMemoryError",
                "-XX:-OmitStackTraceInFastThrow",
                "-XX:+IgnoreUnrecognizedVMOptions",
                "-XX:CompileCommand=exclude,com/intellij/openapi/vfs/impl/FilePartNodeRoot,trieDescend",
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
                "--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
                "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
                "--add-opens=java.base/sun.security.ssl=ALL-UNNAMED",
                "--add-opens=java.base/sun.security.util=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.image=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing.plaf.basic=ALL-UNNAMED",
                "--add-opens=java.desktop/javax.swing.text.html=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt.image=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                "--add-opens=jdk.attach/sun.tools.attach=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-opens=jdk.internal.jvmstat/sun.jvmstat.monitor=ALL-UNNAMED",
                "--add-opens=jdk.jdi/com.sun.tools.jdi=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
                "--add-opens=java.desktop/com.sun.java.swing.plaf.gtk=ALL-UNNAMED",
                
                "--class-path",
                IntelliJTestDriverClasspath,
                "com.yandex.yatagan.intellij.testing.Main",
                workingDir.absolutePath,
                CompiledApiClasspath,
                // System properties
            )
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val error = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        println("STDERR:\n$error\n")
        println("STDOUT:\n$output\n")
        check(exitCode == 0) {
            "test process exit code is not zero: $exitCode"
        }

        val goldenOutput = javaClass.getResourceAsStream("/$goldenResourcePath")?.bufferedReader()?.readText() ?: ""
        val strippedLog = normalizeMessages(output)

        Assert.assertEquals(goldenOutput, strippedLog)
    }
}