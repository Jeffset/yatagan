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
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiElement
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.panel
import com.yandex.yatagan.base.castOrNull
import com.yandex.yatagan.core.graph.impl.BindingGraph
import com.yandex.yatagan.core.model.impl.ComponentModel
import com.yandex.yatagan.intellij.ui.Icons
import com.yandex.yatagan.intellij.lang.extra.AnalysisScope
import com.yandex.yatagan.intellij.services.LexicalScopeService
import com.yandex.yatagan.validation.RichString
import com.yandex.yatagan.validation.ValidationMessage
import com.yandex.yatagan.validation.impl.validate
import kotlinx.coroutines.runBlocking
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UIdentifier
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import java.awt.Dimension
import java.awt.event.MouseEvent
import javax.swing.JComponent

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
                            element, element.textRange, Icons.RootComponent, null, NavProvider(),
                            GutterIconRenderer.Alignment.LEFT,
                        )
                    } else null
                }

                else -> null
            }
        }
    }

    private class ValidateGraphTask(
        private val psi: PsiElement,
    ) : Task.Modal(psi.project, "Building and validating Yatagan Component", true) {
        override fun run(progress: ProgressIndicator) {
            val service = LexicalScopeService.getInstance(project)
            runBlocking {
                val result: ValidationResult = service.lexicalScope.analyze(psi) {
                    doAnalyze(progress)
                }
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is ValidationResult.Error -> notification(project, result.message, NotificationType.ERROR)
                        is ValidationResult.Ok -> notification(
                            project, "Everything is in order", NotificationType.INFORMATION)

                        is ValidationResult.Messages -> {
                            JBPopupFactory.getInstance().createComponentPopupBuilder(
                                createComponent(result.messages), null
                            ).setShowBorder(true)
                                .setMovable(true)
                                .setTitle("$psi analysis results")
                                .createPopup()
                                .showCenteredInCurrentWindow(project)
                        }
                    }
                }
            }
        }

        private fun AnalysisScope.doAnalyze(progress: ProgressIndicator): ValidationResult {
            val messages = run {
                progress.isIndeterminate = false
                progress.fraction = 0.0
                val declaration = getTypeDeclaration(
                    psi.parent.toUElementOfType<UClass>()!!.javaPsi)
                progress.fraction = 0.1
                val component = ComponentModel(declaration)
                progress.fraction = 0.15
                if (!component.isRoot) {
                    return ValidationResult.Error("Non-root component")
                }
                val graph = BindingGraph(
                    root = component,
                )
                progress.fraction = 0.7
                validate(graph).also {
                    progress.fraction = 1.0
                }
            }
            if (messages.isEmpty())
                return ValidationResult.Ok

            return ValidationResult.Messages(
                messages = messages.map { message ->
                    ExtractedLocatedMessage(
                        message = message.message,
                        encounterPaths = message.encounterPaths.map { path ->
                            path.map {
                                it.toString(null).toHtmlString() to
                                        it.langModel?.platformModel?.castOrNull<NavigatablePsiElement>()
                            }
                        }
                    )
                })
        }

        private fun createComponent(messages: Collection<ExtractedLocatedMessage>): JComponent {
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
                                    val (string, psi) = encounterPath[i]
                                    row {
                                        if (psi != null) {
                                            text("in $string (<a>Navigate...</a>)",
                                                action = { psi.navigate(false) })
                                        } else {
                                            text(string)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.let { JBScrollPane(it) }.apply {
                preferredSize = Dimension(1200, 800)
            }
        }

        private fun notification(project: Project, message: String, type: NotificationType) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("YG Notification Group")
                .createNotification("YG message", message, type)
                .notify(project)
        }
    }

    private class NavProvider : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(e: MouseEvent, psi: PsiElement) {
            ProgressManager.getInstance().run(ValidateGraphTask(psi))
        }
    }
}

private data class ExtractedLocatedMessage(
    /**
     * Message payload.
     */
    val message: ValidationMessage,

    /**
     * A list of encounter paths, where the [message] was reported.
     */
    val encounterPaths: List<List<Pair<String, NavigatablePsiElement?>>>,
)

private sealed interface ValidationResult {
    data object Ok : ValidationResult
    class Error(val message: String) : ValidationResult
    class Messages(val messages: List<ExtractedLocatedMessage>) : ValidationResult
}

private fun CharSequence.toHtmlString(): String = when (this) {
    is RichString -> toString()
    else -> toString()
}