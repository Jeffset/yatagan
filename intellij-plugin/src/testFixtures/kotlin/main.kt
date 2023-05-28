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
import com.yandex.yatagan.intellij.lang.TypeDeclaration
import com.yandex.yatagan.lang.BuiltinAnnotation
import com.yandex.yatagan.lang.LangModelFactory
import com.yandex.yatagan.lang.use
import com.yandex.yatagan.processor.common.Logger
import com.yandex.yatagan.processor.common.LoggerDecorator
import com.yandex.yatagan.validation.ValidationMessage
import com.yandex.yatagan.validation.format.format
import com.yandex.yatagan.validation.impl.validate
import org.jetbrains.uast.UFile
import org.jetbrains.uast.toUElement
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // First arg - Project's root directory
    // Second arg - Project's library classpath
    val driver = Driver()
    val output = driver.runTest(
        projectDir = File(args[0]),
        apiClasspath = args[1],
    )
    println(output)
    exitProcess(0)
}

@Suppress("JUnitMalformedDeclaration")
private class Driver : LightJavaCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor() = object : ProjectDescriptor(LanguageLevel.JDK_1_8) {
        override fun getSdk(): Sdk {
            val jdkPath = checkNotNull(System.getProperty("java.home"))
            return IdeaTestUtil.createMockJdk("TestJDK", jdkPath)
        }
    }

    fun runTest(
        projectDir: File,
        apiClasspath: String,
    ): String {
        try {
            setUp()
            return runTestImpl(
                projectDir = projectDir,
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