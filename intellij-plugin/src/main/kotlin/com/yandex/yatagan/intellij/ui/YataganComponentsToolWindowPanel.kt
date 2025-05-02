package com.yandex.yatagan.intellij.ui

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.util.flow.debounceBatch
import com.yandex.yatagan.base.cast
import com.yandex.yatagan.core.graph.impl.BindingGraph
import com.yandex.yatagan.intellij.lang.extra.ComponentModelRef
import com.yandex.yatagan.intellij.name
import com.yandex.yatagan.intellij.services.LexicalScopeService
import com.yandex.yatagan.intellij.services.LexicalScopeService.ComponentSearchEvent
import com.yandex.yatagan.lang.compiled.CtTypeNameModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.tree.DefaultTreeModel
import kotlin.time.Duration.Companion.milliseconds

class YataganComponentsToolWindowPanel(
    private val project: Project,
    private val toolWindow: ToolWindow,
) : SimpleToolWindowPanel(true) {

    val title: String
        get() = "Yatagan Components"

    private val componentListModel = DefaultListModel<ListItem>()
    private val componentList = JBList(componentListModel).apply {
        cellRenderer = object : ColoredListCellRenderer<ListItem>() {
            override fun customizeCellRenderer(
                list: JList<out ListItem>,
                value: ListItem,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                icon = Icons.RootComponent
                append(value.name.toColoredText())
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount == 2) {
                    val itemIndex = this@apply.locationToIndex(event.point)
                    val item = componentListModel[itemIndex]
                    item.ref.source?.navigationElement?.cast<NavigationItem>()?.navigate(true)
                }
            }
        })
    }
    private val splitter = JBSplitter(/*vertical=*/true)

    private val windowScope = CoroutineScope(Dispatchers.Default)

    init {
        setContent(createContent())

        val actionGroup = DefaultActionGroup()
        actionGroup.add(RefreshAction())
        actionGroup.add(BuildAction())

        val toolbar = ActionManager.getInstance().createActionToolbar("YataganComponents", actionGroup, true)
        setToolbar(toolbar.component)

        toolbar.targetComponent = content

        windowScope.launch {
            val service = LexicalScopeService.getInstance(project)
            service
                .allComponentsSearchFlow
                .debounceBatch(500.milliseconds) // Debounce is required not to run `analyze` too often
                .collect { events ->
                    val componentRefs = arrayListOf<ComponentModelRef>()
                    for (event in events) when(event) {
                        is ComponentSearchEvent.CachedCandidates -> {
                            componentRefs.addAll(event.candidates)
                        }
                        is ComponentSearchEvent.FoundCandidate -> {
                            componentRefs.add(event.candidate)
                        }
                        ComponentSearchEvent.SearchCompleted -> {
                            withContext(Dispatchers.EDT) {
                                componentList.setPaintBusy(false)
                            }
                        }
                        ComponentSearchEvent.SearchStarted -> {
                            componentRefs.clear()
                            withContext(Dispatchers.EDT) {
                                componentList.setPaintBusy(true)
                                componentListModel.removeAllElements()
                            }
                        }
                    }
                    val context = componentRefs.firstNotNullOfOrNull { it.source }
                    if (context != null) {
                        // TODO: Use something like "Lite" lexical scope without caching to speed up easy narrow
                        //  analysis
                        val items = service.lexicalScope.analyze(context) {
                            componentRefs.mapNotNull { ref ->
                                ref.get()?.takeIf { it.isRoot }?.let { component ->
                                    ListItem(
                                        ref = ref,
                                        name = component.type.name(),
                                    )
                                }
                            }
                        }
                        withContext(Dispatchers.EDT) {
                            componentListModel.addAll(items)
                        }
                    }
                }
        }
        Disposer.register(toolWindow.disposable) {
            windowScope.cancel()
        }
    }

    private fun createContent(): JComponent = JPanel(BorderLayout()).apply {
        add(splitter, BorderLayout.CENTER)
        val topPanel = ScrollPaneFactory.createScrollPane(componentList)
        splitter.firstComponent = topPanel
    }

    private inner class RefreshAction : AnAction() {
        init {
            ActionUtil.copyFrom(this, IdeActions.ACTION_REFRESH)
        }

        override fun actionPerformed(e: AnActionEvent) {
            LexicalScopeService.getInstance(project).refreshComponents()
        }
    }

    private inner class BuildAction : AnAction() {
        init {
            ActionUtil.copyFrom(this, IdeActions.ACTION_COMPILE_PROJECT)
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
            return ActionUpdateThread.EDT
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = componentList.selectedValue != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val selectedComponent = componentList.selectedValue?.ref ?: return
            val service = LexicalScopeService.getInstance(e.project ?: return)

            service.coroutineScope.launch {
                val context = selectedComponent.source ?: return@launch
                val rootNode = GraphPresentationNode(GraphTreeNode.Root)
                val root: GraphPresentationNode? = service.lexicalScope.analyze(context) {
                    selectedComponent.get()?.let { component ->
                        val graph = BindingGraph(component)
                        createNodeForGraph(graph)
                    }
                }
                rootNode.add(root ?: return@launch)
                val model = DefaultTreeModel(rootNode)
                withContext(Dispatchers.EDT) {
                    splitter.secondComponent =
                        ScrollPaneFactory.createScrollPane(ComponentTreePanel(model))
                }
            }
        }
    }

    private class ListItem(
        val ref: ComponentModelRef,
        val name: CtTypeNameModel,
    )
}
