package com.yandex.yatagan.intellij

import com.intellij.icons.AllIcons
import com.intellij.ide.hierarchy.ChangeHierarchyViewActionBase
import com.intellij.ide.hierarchy.HierarchyBrowser
import com.intellij.ide.hierarchy.HierarchyBrowserBaseEx
import com.intellij.ide.hierarchy.HierarchyBrowserManager
import com.intellij.ide.hierarchy.HierarchyNodeDescriptor
import com.intellij.ide.hierarchy.HierarchyProvider
import com.intellij.ide.hierarchy.HierarchyTreeStructure
import com.intellij.ide.hierarchy.JavaHierarchyUtil
import com.intellij.ide.hierarchy.actions.BrowseHierarchyActionBase
import com.intellij.ide.util.treeView.AlphaComparator
import com.intellij.ide.util.treeView.NodeDescriptor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ui.util.CompositeAppearance
import com.intellij.openapi.util.Comparing
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.presentation.java.ClassPresentationUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.NamedColorUtil
import com.yandex.yatagan.core.model.ComponentModel
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.core.model.impl.ComponentModel
import com.yandex.yatagan.core.model.impl.ModuleModel
import com.yandex.yatagan.intellij.lang.TypeDeclaration
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getUastParentOfType
import org.jetbrains.uast.toUElementOfType
import javax.swing.JPanel
import javax.swing.JTree

class YataganBrowseModulesHierarchy : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        withYataganCacheScope(e.project!!) {
            e.presentation.isEnabled = moduleOrComponentFromAction(
                onModule = { it },
                onComponent = { it },
                event = e,
            ) != null
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments() // prevents problems with smart pointers creation

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Building modules hierarchy", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    val provider = ModulesHierarchyProvider()
                    val target = provider.getTarget(e.dataContext) ?: return@runReadAction
                    ApplicationManager.getApplication().invokeLater({
                        if (!project.isDisposed) {
                            BrowseHierarchyActionBase.createAndAddToPanel(project, provider, target)
                        }
                    }, ModalityState.NON_MODAL)
                }
            }
        })
    }

    private inline fun <R> moduleOrComponentFromAction(
        onModule: (ModuleModel) -> R,
        onComponent: (ComponentModel) -> R,
        event: AnActionEvent,
    ): R? {
        val psi = CommonDataKeys.PSI_ELEMENT.getData(event.dataContext)
        val clazz: UClass? = psi.getUastParentOfType<UClass>(strict = false)

        if (clazz == null) {
            event.presentation.isEnabled = false
            return null
        }

        val declaration = TypeDeclaration(clazz)
        return when {
            declaration.isModule() -> onModule(ModuleModel(declaration))
            declaration.isComponent() -> onComponent(ComponentModel(declaration))
            else -> null
        }
    }

    private class ModulesHierarchyProvider : HierarchyProvider {
        override fun getTarget(dataContext: DataContext): PsiElement? {
            val element = CommonDataKeys.PSI_ELEMENT.getData(dataContext)
            val clazz: UClass = element.getUastParentOfType<UClass>(strict = false) ?: return null
            return clazz.sourcePsi.takeIf {
                withYataganCacheScope(element!!.project) { TypeDeclaration(clazz).run { isModule() || isComponent() } }
            }
        }

        override fun createHierarchyBrowser(target: PsiElement): HierarchyBrowser {
            return ModulesHierarchyBrowser(
                project = target.project,
                root = target,
            )
        }

        override fun browserActivated(hierarchyBrowser: HierarchyBrowser) {
            val browser = hierarchyBrowser as ModulesHierarchyBrowser
            browser.changeView(ModulesHierarchyBrowser.hierarchyView)
        }
    }

    internal class ModulesHierarchyBrowser(
        project: Project,
        root: PsiElement,
    ) : HierarchyBrowserBaseEx(project, root) {

        override fun prependActions(actionGroup: DefaultActionGroup) {
            actionGroup.add(SwitchFlattenedModulesAction())
            actionGroup.add(SwitchModulesHierarchyAction())
            actionGroup.add(AlphaSortAction())
        }

        override fun getElementFromDescriptor(descriptor: HierarchyNodeDescriptor): PsiElement? {
            return descriptor.psiElement
        }

        override fun getPrevOccurenceActionNameImpl(): String {
            return YataganBundle.message("yatagan.modules.occurrence.previous")
        }

        override fun getNextOccurenceActionNameImpl(): String {
            return YataganBundle.message("yatagan.modules.occurrence.next")
        }

        override fun createTrees(trees: MutableMap<in String, in JTree>) {
            trees[hierarchyView] = createTree(false)
            trees[flattenedView] = createTree(false)
        }

        override fun createLegendPanel(): JPanel? = null

        override fun isApplicableElement(element: PsiElement): Boolean {
            val clazz = element.toUElementOfType<UClass>() ?: return false
            return withYataganCacheScope(element.project) {
                TypeDeclaration(clazz).run { isModule() || isComponent() }
            }
        }

        override fun createHierarchyTreeStructure(type: String, psiElement: PsiElement): HierarchyTreeStructure {
            return ModulesTreeStructure(
                project = myProject,
                baseDescriptor = ModulesHierarchyNodeDescriptor(
                    project = myProject,
                    element = psiElement,
                    parent = null,
                    isBase = true,
                ),
                kind = when(type) {
                    hierarchyView -> ModulesTreeStructure.Kind.Hierarchy
                    flattenedView -> ModulesTreeStructure.Kind.Flattened
                    else -> throw AssertionError(type)
                }
            )
        }

        override fun getComparator(): Comparator<NodeDescriptor<*>> {
            val state = HierarchyBrowserManager.getInstance(myProject).state
            return if (state?.SORT_ALPHABETICALLY == true) {
                AlphaComparator.INSTANCE
            } else DefaultComparator
        }

        override fun getActionPlace() = ActionPlaces.CALL_HIERARCHY_VIEW_TOOLBAR

        companion object {
            val hierarchyView get() = YataganBundle.message("yatagan.modules.included.into")
            val flattenedView get() = YataganBundle.message("yatagan.modules.included.into.flattened")
        }

        object DefaultComparator : Comparator<NodeDescriptor<*>> {
            override fun compare(d1: NodeDescriptor<*>, d2: NodeDescriptor<*>): Int {
                return d1.index.compareTo(d2.index)
            }
        }
    }

    internal class ModulesTreeStructure(
        private val kind: Kind,
        project: Project,
        baseDescriptor: ModulesHierarchyNodeDescriptor,
    ) : HierarchyTreeStructure(project, baseDescriptor) {
        enum class Kind {
            Hierarchy,
            Flattened,
        }

        override fun buildChildren(descriptor: HierarchyNodeDescriptor): Array<Any> {
            if (descriptor !is ModulesHierarchyNodeDescriptor)
                return emptyArray()

            val clazz = descriptor.psiElement.toUElementOfType<UClass>() ?: return emptyArray()
            val children = withYataganCacheScope(descriptor.project) {
                val declaration = TypeDeclaration(clazz)
                val modules = when {
                    declaration.isModule() -> ModuleModel(declaration).run {
                        when(kind) {
                            Kind.Hierarchy -> includes
                            Kind.Flattened -> if (descriptor == myBaseDescriptor) allIncludes() else emptyList()
                        }
                    }
                    declaration.isComponent() -> ComponentModel(declaration).run {
                        when(kind) {
                            Kind.Hierarchy -> modules
                            Kind.Flattened -> if (descriptor == myBaseDescriptor) allModules else emptyList()
                        }
                    }
                    else -> emptyList()
                }
                modules.map {
                    ModulesHierarchyNodeDescriptor(
                        project = myProject,
                        element = it.type.declaration.platformModel as PsiElement,
                        parent = descriptor,
                        isBase = false,
                    )
                }
            }
            return children.toTypedArray()
        }
    }

    internal class ModulesHierarchyNodeDescriptor(
        project: Project,
        element: PsiElement,
        parent: HierarchyNodeDescriptor?,
        isBase: Boolean = false,
    ) : HierarchyNodeDescriptor(project, parent, element, isBase) {
        override fun update(): Boolean {
            var changes = super.update()
            val uClass = psiElement?.toUElementOfType<UClass>()
            val clazz = uClass?.javaPsi

            val newText = CompositeAppearance()
            if (clazz != null) {
                val needsInstance = withYataganCacheScope(clazz.project) {
                    val declaration = TypeDeclaration(uClass)
                    if (declaration.isModule()) {
                        ModuleModel(declaration).let { it.requiresInstance && !it.isTriviallyConstructable }
                    } else null
                }
                newText.ending.addText(ClassPresentationUtil.getNameForClass(clazz, false))
                newText.ending.addText(" (${JavaHierarchyUtil.getPackageName(clazz)})", getPackageNameAttributes())
                when (needsInstance) {
                    true -> newText.ending.addText(" [requires instance]", instanceModuleAttributes)
                    false -> newText.ending.addText(" [static]", staticModuleAttributes)
                    null -> {}
                }
            } else {
                newText.beginning.addText("[Invalid]")
            }

            if (!Comparing.equal(newText, myHighlightedText)) {
                changes = true
                myHighlightedText = newText
                myName = newText.text
            }
            return changes
        }

        companion object {
            val staticModuleAttributes = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_PLAIN, NamedColorUtil.getInactiveTextColor(),
            )
            val instanceModuleAttributes = SimpleTextAttributes(
                SimpleTextAttributes.STYLE_PLAIN, JBColor.namedColor("FileColor.Orange", JBColor(0xf6e9dc, 0x806052)),
            )
        }
    }
}

class SwitchFlattenedModulesAction() : ChangeHierarchyViewActionBase(
    YataganBundle.message("yatagan.modules.flattened"),
    YataganBundle.message("yatagan.modules.flattened.description"),
    AllIcons.Actions.ListFiles,
) {
    override fun getTypeName(): String {
        return YataganBrowseModulesHierarchy.ModulesHierarchyBrowser.flattenedView
    }
}

class SwitchModulesHierarchyAction() : ChangeHierarchyViewActionBase(
    YataganBundle.message("yatagan.modules.hierarchy"),
    YataganBundle.message("yatagan.modules.hierarchy.description"),
    AllIcons.Actions.ShowAsTree,
) {
    override fun getTypeName(): String {
        return YataganBrowseModulesHierarchy.ModulesHierarchyBrowser.hierarchyView
    }
}