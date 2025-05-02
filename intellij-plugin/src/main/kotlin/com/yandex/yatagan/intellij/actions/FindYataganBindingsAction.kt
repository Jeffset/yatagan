package com.yandex.yatagan.intellij.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiCapturedWildcardType
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiSearchHelper
import com.intellij.psi.search.SearchScope
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.tree.TreeVisitor
import com.intellij.util.ui.tree.TreeUtil
import com.yandex.yatagan.base.api.childrenSequence
import com.yandex.yatagan.base.api.parentsSequence
import com.yandex.yatagan.core.graph.impl.BindingGraph
import com.yandex.yatagan.core.model.impl.NodeModel
import com.yandex.yatagan.intellij.lang.IJTypeImpl
import com.yandex.yatagan.intellij.resolveBindingOrNull
import com.yandex.yatagan.intellij.services.LexicalScopeService
import com.yandex.yatagan.intellij.services.LexicalScopeService.ComponentSearchEvent
import com.yandex.yatagan.intellij.ui.GraphTreeNode
import com.yandex.yatagan.intellij.ui.ComponentTreePanel
import com.yandex.yatagan.intellij.ui.GraphPresentationNode
import com.yandex.yatagan.intellij.ui.createNodeForGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.uast.UTypeReferenceExpression
import org.jetbrains.uast.findUElementAt
import java.awt.Dimension
import javax.swing.tree.DefaultTreeModel

class FindYataganBindingsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val file = e.dataContext.getData(CommonDataKeys.PSI_FILE) ?: return
        val editor = e.dataContext.getData(CommonDataKeys.EDITOR) ?: return
        val type = file.findUElementAt(editor.caretModel.offset, UTypeReferenceExpression::class.java)?.type ?: return

        val useScope = type.accept(PsiContextFromType(file.project))

        val rootNode = GraphPresentationNode(GraphTreeNode.Root)
        val model = DefaultTreeModel(rootNode)
        val treePanel = ComponentTreePanel(model)
        treePanel.tree.setPaintBusy(true)
        val jbPopupFactory = JBPopupFactory.getInstance()
        val popupComponent = ScrollPaneFactory.createScrollPane(treePanel)
        jbPopupFactory.createComponentPopupBuilder(popupComponent, popupComponent)
            .setTitle("All bindings to ${type.canonicalText} in the project")
            .setMovable(true)
            .setResizable(true)
            .setFocusable(true)
            .setMinSize(Dimension(1000, 700))
            .createPopup()
            .showInBestPositionFor(editor)

        val service = LexicalScopeService.getInstance(file.project)
        service.refreshComponents()
        service.coroutineScope.launch {
            service.lexicalScope.analyze(file) {
                val typeModel = with(lexicalScope) { IJTypeImpl(type) }
                val nodeModel = NodeModel(typeModel)
                runBlocking {
                    service.allComponentsSearchFlow.takeWhile {
                        it !== ComponentSearchEvent.SearchCompleted
                    }.collect { event ->
                        val componentsRefs = when(event) {
                            ComponentSearchEvent.SearchStarted -> emptyList<Nothing>().also {
                                rootNode.removeAllChildren()
                            }
                            is ComponentSearchEvent.CachedCandidates -> event.candidates
                            is ComponentSearchEvent.FoundCandidate -> listOf(event.candidate)
                            ComponentSearchEvent.SearchCompleted -> throw AssertionError()
                        }
                        for (componentRef in componentsRefs) {
                            val virtualFile = componentRef.source?.containingFile?.virtualFile
                            if (virtualFile == null || !useScope.contains(virtualFile)) continue
                            val component = componentRef.get()?.takeIf { it.isRoot } ?: continue

                            val included = hashSetOf<Any>()
                            val rootGraph = BindingGraph(component)
                            rootGraph.childrenSequence().mapNotNullTo(included) { graph ->
                                graph.resolveBindingOrNull(nodeModel)?.takeIf { it.owner == graph }?.also {
                                    included.addAll(graph.parentsSequence(includeThis = true))
                                }
                            }
                            if (included.isNotEmpty()) {
                                val newNode = createNodeForGraph(
                                    bindingGraph = rootGraph,
                                    filter = { it in included },
                                    useLabels = false,
                                )
                                launch(Dispatchers.EDT) {
                                    model.insertNodeInto(newNode, rootNode, rootNode.childCount)
                                    TreeUtil.expand(treePanel.tree, { path ->
                                        val node = path.lastPathComponent as GraphPresentationNode
                                        when(node.userObject) {
                                            is GraphTreeNode.BindingModel -> TreeVisitor.Action.SKIP_CHILDREN
                                            else -> TreeVisitor.Action.CONTINUE
                                        }
                                    }, {})
                                }
                            }
                        }
                    }
                }
            }
            withContext(Dispatchers.EDT) {
                treePanel.tree.setPaintBusy(false)
            }
        }
    }

    private class PsiContextFromType(
        private val project: Project,
    ) : PsiTypeVisitor<SearchScope>() {
        private val helper = PsiSearchHelper.getInstance(project)

        override fun visitType(type: PsiType): SearchScope = GlobalSearchScope.allScope(project)
        override fun visitClassType(classType: PsiClassType): SearchScope {
            val scopes = mutableListOf(classType.resolve()?.let(helper::getUseScope))
            classType.parameters.forEach {
                scopes.add(it.accept(this))
            }
            return scopes.filterNotNull()
                .fold(GlobalSearchScope.everythingScope(project), SearchScope::intersectWith)
        }
        override fun visitArrayType(arrayType: PsiArrayType): SearchScope = arrayType.componentType.accept(this)
        override fun visitWildcardType(wildcardType: PsiWildcardType) = wildcardType.bound?.accept(this)
        override fun visitCapturedWildcardType(capturedWildcardType: PsiCapturedWildcardType): SearchScope =
            capturedWildcardType.wildcard.accept(this)
    }
}