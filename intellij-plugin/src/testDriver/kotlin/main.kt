@file:JvmName("Main")

package com.yandex.yatagan.intellij.testing

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.yandex.yatagan.core.graph.impl.BindingGraph
import com.yandex.yatagan.core.model.impl.ComponentModel
import com.yandex.yatagan.intellij.lang.IJLexicalScope
import com.yandex.yatagan.intellij.testing.rmi.IntelliJTestService
import com.yandex.yatagan.lang.BuiltinAnnotation
import com.yandex.yatagan.processor.common.Logger
import com.yandex.yatagan.processor.common.LoggerDecorator
import com.yandex.yatagan.validation.ValidationMessage
import com.yandex.yatagan.validation.format.format
import com.yandex.yatagan.validation.impl.validate
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElement
import java.io.File
import java.rmi.registry.LocateRegistry
import java.rmi.server.UnicastRemoteObject
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private const val TIMEOUT = 30_000L

fun main(args: Array<String>) {
    val (apiClasspath, jdkPath) = args
    println("SERVICE: Launched")

//    val executorService = Executors.newSingleThreadExecutor()
//    val dispatcher = executorService.asCoroutineDispatcher()
    runBlocking {
        val driver = async {
            // Heavy classloading and initialization, has to be async
            Driver(
                apiClasspath = apiClasspath,
                jdkPath = jdkPath,
            )
        }

        fun startTimeoutCountdown() {
            launch { delay(TIMEOUT) }
        }

        val service = object : IntelliJTestService {
            override fun runTest(projectDirectory: String): String {
                println("SERVICE: Running test in $projectDirectory")
                return runBlocking {
                    driver.await().runTest(projectDirectory)
                }.also {
                    startTimeoutCountdown()
                    println("SERVICE: Done running test in $projectDirectory")
                }
            }
        }

        LocateRegistry.createRegistry(IntelliJTestService.RMI_PORT)
            .bind(IntelliJTestService::class.qualifiedName, UnicastRemoteObject.exportObject(service, 0))

        startTimeoutCountdown()
    }
    println("SERVICE: Exit")
    exitProcess(0)
}

@Suppress("JUnitMalformedDeclaration")
private class Driver(
    private val apiClasspath: String,
    private val jdkPath: String,
) : LightJavaCodeInsightFixtureTestCase(), IntelliJTestService {
    override fun getProjectDescriptor() = object : ProjectDescriptor(LanguageLevel.JDK_1_8) {
        override fun getSdk(): Sdk {
            return IdeaTestUtil.createMockJdk("TestJDK", jdkPath)
        }
    }

    override fun runTest(projectDirectory: String): String {
        try {
            setUp()
            return runTestImpl(
                projectDir = File(projectDirectory),
                apiClasspath = apiClasspath,
            )
        } finally {
            tearDown()
        }
    }

    private fun runTestImpl(
        projectDir: File,
        apiClasspath: String,
    ): String {
        ModuleRootModificationUtil.modifyModel(module) { model ->
            PsiTestUtil.addLibrary(model, "dl-api-classpath", "/",
                *apiClasspath.split(':')
                    .map { File(it).absolutePath }
                    .toTypedArray()
            )
            true
        }

        val sourceExtensions = setOf("kt", "java")
        val files = projectDir.walkBottomUp()
            .filter { it.isFile && it.extension in sourceExtensions }
            .map { source ->
                val path = source.toRelativeString(projectDir)
                myFixture.addFileToProject(path, source.readText())
            }
            .toList()

        val recordingLogger = RecordingLogger()
        val logger = LoggerDecorator(recordingLogger)
        val lexicalScope = IJLexicalScope(myFixture.project)
        ReadAction.run<Nothing> {
            try {
                lexicalScope.analyze(files.first()) {
                    val allRootComponents = files.flatMap { file ->
                        file?.toUElement(UFile::class.java)
                            ?.allClasses()
                            ?.map { getTypeDeclaration(it.javaPsi) }
                            ?: emptyList()
                    }.filter {
                        it.getAnnotation(BuiltinAnnotation.Component)?.isRoot == true
                    }

                    check(allRootComponents.isNotEmpty()) {
                        "No root components detected! Check the test source/intellij test driver"
                    }

                    for (rootComponent in allRootComponents) {
                        val graph = BindingGraph(
                            root = ComponentModel(rootComponent),
                        )
                        val locatedMessages = validate(graph)
                        for (message in locatedMessages) {
                            val text = message.format(maxEncounterPaths = 1000).toString()
                            when (message.message.kind) {
                                ValidationMessage.Kind.Error,
                                ValidationMessage.Kind.MandatoryWarning,
                                -> logger.error(text)

                                ValidationMessage.Kind.Warning -> logger.warning(text)
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                logger.error(e.stackTraceToString())
            }
        }
        return recordingLogger.getLog()
    }

    private class RecordingLogger : Logger {
        private val log = StringBuilder()

        fun getLog(): String = log.toString()

        override fun error(message: String) {
            log.appendLine(message)
        }

        override fun warning(message: String) {
            log.appendLine(message)
        }
    }
}