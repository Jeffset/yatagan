@file:JvmName("Main")

package com.yandex.yatagan.intellij.testing

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.pom.java.LanguageLevel
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.yandex.yatagan.base.ObjectCacheRegistry
import com.yandex.yatagan.core.graph.impl.BindingGraph
import com.yandex.yatagan.core.graph.impl.Options
import com.yandex.yatagan.core.model.impl.ComponentModel
import com.yandex.yatagan.intellij.lang.IjModelFactoryImpl
import com.yandex.yatagan.intellij.lang.ProcessingUtils
import com.yandex.yatagan.intellij.lang.TypeDeclaration
import com.yandex.yatagan.intellij.testing.rmi.IntelliJTestService
import com.yandex.yatagan.lang.BuiltinAnnotation
import com.yandex.yatagan.lang.LangModelFactory
import com.yandex.yatagan.lang.use
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

private const val TIMEOUT = 5_000L

fun main(args: Array<String>) {
    val (apiClasspath, jdkPath) = args

    val executorService = Executors.newSingleThreadExecutor()
    val dispatcher = executorService.asCoroutineDispatcher()
    runBlocking(dispatcher) {
        val driver = async {
            // Heavy classloading and initialization, has to be async
            Driver(
                apiClasspath = apiClasspath,
                jdkPath = jdkPath,
            )
        }

        fun keepAlivePulse() {
            launch {
                delay(TIMEOUT)
            }
        }

        val service = object : IntelliJTestService {
            override fun runTest(projectDirectory: String): String {
                return runBlocking(dispatcher) {
                    driver.await().runTest(projectDirectory).also {
                        keepAlivePulse()
                    }
                }
            }
        }

        LocateRegistry.createRegistry(IntelliJTestService.RMI_PORT)
            .bind(IntelliJTestService::class.qualifiedName, UnicastRemoteObject.exportObject(service, 0))

        keepAlivePulse()
    }
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
        ObjectCacheRegistry.use {
            ProcessingUtils(project).use {
                LangModelFactory.use(IjModelFactoryImpl(project)) {
                    ReadAction.run<Nothing> {
                        try {
                            val allRootComponents = files.flatMap { file ->
                                file?.toUElement(UFile::class.java)
                                    ?.allClasses()
                                    ?.map { TypeDeclaration(it) }
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
                                    options = Options(),
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
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    }
                }
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