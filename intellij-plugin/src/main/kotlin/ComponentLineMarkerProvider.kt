package com.yandex.yatagan.intellij

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.util.ProgressWrapper
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.concurrency.AppExecutorUtil
import com.yandex.yatagan.base.ObjectCacheRegistry
import com.yandex.yatagan.core.graph.impl.BindingGraph
import com.yandex.yatagan.core.graph.impl.Options
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.core.model.impl.ComponentModel
import com.yandex.yatagan.intellij.lang.IjModelFactoryImpl
import com.yandex.yatagan.intellij.lang.TypeDeclaration
import com.yandex.yatagan.lang.LangModelFactory
import com.yandex.yatagan.lang.use
import com.yandex.yatagan.validation.LocatedMessage
import com.yandex.yatagan.validation.RichString
import com.yandex.yatagan.validation.ValidationMessage
import com.yandex.yatagan.validation.impl.validate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UIdentifier
import org.jetbrains.uast.toUElement
import java.awt.Dimension
import java.awt.event.MouseEvent
import javax.swing.JComponent
import kotlin.coroutines.CoroutineContext

class ComponentLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName(): String {
        return "Dagger Lite components"
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        return when (val uelement = element.toUElement(UIdentifier::class.java)) {
            null -> null
            else -> when (val parent = uelement.uastParent) {
                is UClass -> {
                    if (parent.findAnnotation("com.yandex.yatagan.Component") != null) {
                        @Suppress("DEPRECATION")
                        LineMarkerInfo<PsiElement>(
                            element, element.textRange, AllIcons.FileTypes.Any_type, null, NavProvider(),
                            GutterIconRenderer.Alignment.LEFT,
                        )
                    } else null
                }

                else -> null
            }
        }
    }

    private class NavProvider : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, psi: PsiElement) {
            val pointer = SmartPointerManager.createPointer(psi)
            val project = psi.project

            ProgressManager.getInstance().run(object : Task.Modal(project, "Analyzing Yatagan Component", false) {
                override fun run(pi: ProgressIndicator) {
                    val appD = ReadActionDispatcher(AppExecutorUtil.getAppExecutorService().asCoroutineDispatcher())
                    val scope = CoroutineScope(appD)

                    runBlocking {
                        val job = scope.launch {
                            val messages = LangModelFactory.use(IjModelFactoryImpl(project)) {
                                ObjectCacheRegistry.use {
                                    suspend fun Collection<ModuleModel>.warmupModules(): List<ModuleModel> = map {
                                        async {
                                            it.bindings.count()
                                            it.subcomponents.forEach { sub ->
                                                sub.modules.warmupModules()
                                            }
                                            it.multiBindingDeclarations.count()
                                            it.includes.warmupModules()
                                        }
                                    }.flatMap { it.await() }

                                    pi.fraction = 0.0
                                    val element = pointer.element ?: return@launch
                                    val declaration =
                                        TypeDeclaration(element.parent.toUElement(UClass::class.java)!!)
                                    pi.fraction = 0.1
                                    val component = ComponentModel(declaration)
                                    pi.fraction = 0.15

                                    component.modules.warmupModules()

                                    pi.fraction = 0.3
                                    if (component.isRoot) {
                                        val graph = BindingGraph(
                                            root = component,
                                            options = Options(),
                                        )
                                        pi.fraction = 0.7
                                        val messages = validate(graph)
                                        messages
                                    } else {
                                        notification(element.project, "Non-root component", NotificationType.INFORMATION)
                                        null
                                    }.also {
                                        pi.fraction = 1.0
                                    }
                                }
                            }
                            ApplicationManager.getApplication().invokeLater {
                                if (!project.isOpen || messages == null) {
                                    return@invokeLater
                                }
                                if (messages.isEmpty()) {
                                    notification(project, "Everything is in order", NotificationType.INFORMATION)
                                } else {
                                    JBPopupFactory.getInstance().createComponentPopupBuilder(
                                        createComponent(messages), null
                                    ).setShowBorder(true)
                                        .setMovable(true)
                                        .setTitle("${pointer.element} analysis results")
                                        .createPopup()
                                        .showCenteredInCurrentWindow(project)
                                }
                            }
                        }

                        job.join()
                    }
                }
            })

        }

        private fun createComponent(messages: Collection<LocatedMessage>): JComponent {
            return panel {
                row {
                    label("Total ${messages.size} messages:")
                }
                for (message in messages) {
                    group {
                        row {
                            icon(when (message.message.kind) {
                                ValidationMessage.Kind.Error -> AllIcons.General.Error
                                ValidationMessage.Kind.MandatoryWarning -> AllIcons.General.Warning
                                ValidationMessage.Kind.Warning -> AllIcons.General.Warning
                            })
                            text(message.message.contents.toHtmlString())
                        }
                        if (message.message.notes.isNotEmpty()) {
                            collapsibleGroup("Notes") {
                                for (note in message.message.notes) {
                                    row {
                                        icon(AllIcons.General.Information)
                                        text(note.toHtmlString())
                                    }
                                }
                            }
                        }
                        for (encounterPath in message.encounterPaths) {
                            collapsibleGroup("Encountered in") {
                                for (i in encounterPath.indices) {
                                    val element = encounterPath[i]
                                    val string = element.toString(childContext = encounterPath.getOrNull(i + 1))
                                    val psi = element.langModel?.platformModel as? Navigatable
                                    row {
                                        if (psi != null) {
                                            text("in ${string.toHtmlString()} (<a>Navigate...</a>)",
                                                action = { psi.navigate(false) })
                                        } else {
                                            text(string.toHtmlString())
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.apply {
                preferredSize = Dimension(1200, 800)
            }
        }

        private fun CharSequence.toHtmlString() = when (this) {
            is RichString -> toString()
            else -> toString()
        }

        private fun notification(project: Project, message: String, type: NotificationType) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("YG Notification Group")
                .createNotification("YG message", message, type)
                .notify(project)
        }
    }
}

private class ReadActionDispatcher(
    private val underlying: CoroutineDispatcher,
) : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val pi = ProgressManager.getInstance().progressIndicator
        val indicator = ProgressWrapper.wrap(pi)
        underlying.dispatch(context) nestedDispatch@ {
            repeat(100) {
                if (ProgressManager.getInstance().runInReadActionWithWriteActionPriority(block, indicator))
                    return@nestedDispatch
                Thread.sleep(10)
            }
        }
    }
}