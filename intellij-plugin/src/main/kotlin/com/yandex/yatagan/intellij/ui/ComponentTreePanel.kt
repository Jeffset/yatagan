package com.yandex.yatagan.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.ui.ColoredText
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeSpeedSearch
import com.intellij.ui.treeStructure.Tree
import com.yandex.yatagan.base.cast
import com.yandex.yatagan.core.graph.bindings.Binding
import com.yandex.yatagan.core.graph.bindings.MapBinding
import com.yandex.yatagan.core.graph.bindings.MultiBinding
import com.yandex.yatagan.core.model.ClassBackedModel
import com.yandex.yatagan.core.model.DependencyKind
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.intellij.data.AnalyzedBindingDependencies
import com.yandex.yatagan.intellij.data.AnalyzedBindingGraph
import com.yandex.yatagan.intellij.data.AnalyzedBindingModel
import com.yandex.yatagan.intellij.data.AnalyzedModuleModel
import com.yandex.yatagan.intellij.data.AnalyzedNodeModel
import com.yandex.yatagan.intellij.data.GraphElement
import com.yandex.yatagan.lang.compiled.ArrayNameModel
import com.yandex.yatagan.lang.compiled.ClassNameModel
import com.yandex.yatagan.lang.compiled.CtTypeBase
import com.yandex.yatagan.lang.compiled.CtTypeNameModel
import com.yandex.yatagan.lang.compiled.InvalidNameModel
import com.yandex.yatagan.lang.compiled.KeywordTypeNameModel
import com.yandex.yatagan.lang.compiled.ParameterizedNameModel
import com.yandex.yatagan.lang.compiled.WildcardNameModel
import com.yandex.yatagan.lang.Annotation
import com.yandex.yatagan.lang.Callable
import com.yandex.yatagan.lang.Constructor
import com.yandex.yatagan.lang.Method
import com.yandex.yatagan.lang.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

class ComponentTreePanel(
    rootsFlow: Flow<AnalyzedBindingGraph>,
    private val coroutineScope: CoroutineScope,
) : SimpleToolWindowPanel(true) {
    private val rootNode = GraphPresentationNode(GraphTreeNode.Root)
    private val model = DefaultTreeModel(rootNode)
    private val roots = arrayListOf<AnalyzedBindingGraph>()

    val tree = Tree(model).apply {
        cellRenderer = BindingGraphTreeCellRenderer()
        isRootVisible = false
    }

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar(
            "YataganComponentTree", createToolbarActionGroup(), /*horizontal*/true).apply {
            targetComponent = this@ComponentTreePanel
        }
        setToolbar(toolbar.component)

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return

                val node = tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? GraphPresentationNode ?: return
                node.userObject.navigateTo?.element?.navigationElement?.cast<NavigationItem>()?.navigate(false)
            }
        })

        TreeSpeedSearch(tree, true) { path ->
            val node = path.lastPathComponent as GraphPresentationNode
            node.userObject.searchableString
        }
        setContent(ScrollPaneFactory.createScrollPane(tree))

        coroutineScope.launch(Dispatchers.EDT) {
            rootsFlow.collect { newRoot ->
                roots.add(newRoot)
                model.insertNodeInto(buildTree(newRoot), rootNode, rootNode.childCount)
            }
        }
    }

    private fun buildTree(root: AnalyzedBindingGraph): GraphPresentationNode {
        // TODO: Pass options
        return createNodeForGraph(root)
    }

    private fun createToolbarActionGroup(): DefaultActionGroup {
        val actionGroup = DefaultActionGroup()
        val navigateAction = object : AnAction(AllIcons.General.Locate) {
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = tree.selectionPath != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                val item = tree.selectionPath?.lastPathComponent as? GraphPresentationNode ?: return
                item.userObject.navigateTo?.element?.navigationElement?.cast<NavigationItem>()?.navigate(false)
            }
        }
        actionGroup.add(navigateAction)
        val groupBindingsByModuleToggle = object : ToggleAction(
            "Group Bindings by Module",
            "Toggles grouping bindings by their module (modules are displayed hierarchically)",
            Icons.GroupByModule,
        ) {
            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }

            override fun isSelected(e: AnActionEvent): Boolean {
                TODO("Not yet implemented")
            }

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                TODO("Not yet implemented")
            }
        }
        actionGroup.add(groupBindingsByModuleToggle)
        return actionGroup
    }
}

data class NodeModelElement(
    val name: CtTypeNameModel,
    val qualifier: String?,
)

private fun ClassBackedModel.getNavigationElement(): SmartPsiElementPointer<PsiElement> {
    return type.declaration.platformModel!!
        .cast<PsiClass>().let { it.nameIdentifier!! }.let(SmartPointerManager::createPointer)
}

private object CallableToShortString : Callable.Visitor<String?> {
    override fun visitOther(callable: Callable) = throw AssertionError()
    override fun visitMethod(method: Method) = buildString {
        // FIXME: Use name model instead of qualifiedName
        append(method.owner.qualifiedName.substringAfterLast('.')).append("::").append(method.name).append('(')
        if (method.parameters.any()) append("...")
        append(')')
    }
    override fun visitConstructor(constructor: Constructor): String? {
        // No additional info
        return null
    }
}

data class NodeDependencyModel(
    val node: NodeModelElement,
    val kind: DependencyKind,
)

private fun NodeModel.toElement() = NodeModelElement(
    name = type.cast<CtTypeBase>().nameModel,
    qualifier = qualifier?.asShortString(),
)

sealed interface GraphTreeNode {
    data object Root : GraphTreeNode

    enum class Label : GraphTreeNode {
        Modules,
        ChildComponents,
        Bindings,
    }

    class Element(
        val data: GraphElement,
    ) : GraphTreeNode
//
//    data class BindingModel(
//        override val navigateTo: SmartPsiElementPointer<PsiElement>?,
//        val target: NodeModelElement,
//        val kind: BindingKind,
//        val isConditional: Boolean,
//        val label: String?,
//    ) : GraphTreeNode {
//        override val searchableString: String = buildString {
//            target.qualifier?.let { append(it).append(' ') }
//            append(target.name.toString())
//        }
//
//        companion object : Binding.Visitor<BindingModel?> {
//            operator fun invoke(binding: Binding): BindingModel? = binding.accept(this)
//
//            @OptIn(Incubating::class)
//            private fun toBaseBinding(
//                binding: Binding,
//                kind: BindingKind,
//                label: String? = null,
//                navigateTo: SmartPsiElementPointer<PsiElement>? = null,
//            ): BindingModel {
//                return BindingModel(
//                    target = binding.target.toElement(),
//                    navigateTo = navigateTo ?: binding.methodModel?.method?.platformModel?.cast<PsiElement>()
//                        ?.let(SmartPointerManager::createPointer),
//                    kind = kind,
//                    isConditional = !binding.conditionScope.isTautology(),
//                    label = binding.methodModel?.method?.accept(CallableToShortString) ?: label,
//                )
//            }
//
//            override fun visitOther(binding: Binding) = null
//
//            override fun visitProvision(binding: ProvisionBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                navigateTo = binding.provision.platformModel?.cast<PsiElement>()
//                    ?.let(SmartPointerManager::createPointer),
//                label = binding.provision.accept(CallableToShortString),
//                kind = binding.provision.accept(CallableToBindingKind),
//            )
//
//            override fun visitAssistedInjectFactory(binding: AssistedInjectFactoryBinding): BindingModel =
//                toBaseBinding(
//                    binding = binding,
//                    navigateTo = binding.model.getNavigationElement(),
//                    kind = BindingKind.AssistedInject,
//                )
//
//            override fun visitInstance(binding: InstanceBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                navigateTo = binding.origin.hasPlatformModel.platformModel
//                    ?.cast<PsiElement>()?.let(SmartPointerManager::createPointer),
//                kind = BindingKind.Instance,
//            )
//
//            @Incubating
//            override fun visitAlternatives(binding: AlternativesBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                kind = BindingKind.Alternatives,
//            )
//
//            override fun visitSubComponent(binding: SubComponentBinding): BindingModel? {
//                // WARNING: Deprecated and removed
//                return null
//            }
//
//            override fun visitComponentDependency(binding: ComponentDependencyBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                navigateTo = binding.owner.model.getNavigationElement(),  // TODO: Maybe navigate to Dep declaration?
//                kind = BindingKind.ComponentDependency,
//            )
//
//            override fun visitComponentInstance(binding: ComponentInstanceBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                navigateTo = binding.owner.model.getNavigationElement(),
//                kind = BindingKind.ComponentInstance,
//            )
//
//            override fun visitComponentDependencyEntryPoint(binding: ComponentDependencyEntryPointBinding): BindingModel =
//                toBaseBinding(
//                    binding = binding,
//                    navigateTo = binding.getter.platformModel?.cast<PsiElement>()
//                        ?.let(SmartPointerManager::createPointer),
//                    label = binding.getter.accept(CallableToShortString),
//                    kind = BindingKind.ComponentDependencyEp,
//                )
//
//            override fun visitMulti(binding: MultiBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                kind = when (binding.kind) {
//                    CollectionTargetKind.List -> BindingKind.List
//                    CollectionTargetKind.Set -> BindingKind.Set
//                },
//            )
//
//            override fun visitMap(binding: MapBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                kind = BindingKind.Map,
//            )
//
//            @Incubating
//            override fun visitConditionExpressionValue(binding: ConditionExpressionValueBinding) = null
//
//            override fun visitEmpty(binding: EmptyBinding): BindingModel = toBaseBinding(
//                binding = binding,
//                kind = if (binding.methodModel == null) BindingKind.Missing else BindingKind.ExplicitEmpty,
//            )
//        }
//    }
}

private fun GraphTreeNode.allowsChildren(): Boolean = when(this) {
    is GraphTreeNode.Root -> true
    is GraphTreeNode.Label -> true
    is GraphTreeNode.Element -> data.accept(AllowsChildren)
}

private object AllowsChildren : GraphElement.Visitor<Boolean> {
    override fun visitGraph(graph: AnalyzedBindingGraph) = true
    override fun visitModule(module: AnalyzedModuleModel) = true
    override fun visitBinding(binding: AnalyzedBindingModel) = true
    override fun visitRegularDependency(dependency: AnalyzedBindingDependencies.Regular.Dependency) = false
    override fun visitAlternative(alternative: AnalyzedBindingDependencies.Alternatives.Alternative) = false
    override fun visitMultibindingContribution(contribution: AnalyzedBindingDependencies.Multibinding.Contribution) = false
}

private fun Annotation.asShortString(): String = buildString {
    append('@')
    append(annotationClass.qualifiedName.substringAfterLast('.'))
    val attributes = annotationClass.attributes
    if (attributes.any()) {
        attributes
            .sortedBy { it.name }
            .joinTo(this, prefix = "(", postfix = ")", separator = ",") {
                getValue(it).accept(ToShortString)
            }
    }
}

private object ToShortString : Annotation.Value.Visitor<String> {
    override fun visitDefault(value: Any?) = throw AssertionError()
    override fun visitBoolean(value: Boolean) = value.toString()
    override fun visitByte(value: Byte) = value.toString()
    override fun visitShort(value: Short) = value.toString()
    override fun visitInt(value: Int) = value.toString()
    override fun visitLong(value: Long) = value.toString()
    override fun visitChar(value: Char) = value.toString()
    override fun visitFloat(value: Float) = value.toString()
    override fun visitDouble(value: Double) = value.toString()
    override fun visitString(value: String) = value
    override fun visitType(value: Type) = "$value"
    override fun visitAnnotation(value: Annotation) = value.asShortString()
    override fun visitEnumConstant(enum: Type, constant: String) = constant
    override fun visitUnresolved(): String = "???"
    override fun visitArray(value: List<Annotation.Value>): String {
        return value.joinToString(prefix = "[", postfix = "]", separator = ",") { it.accept(ToShortString) }
    }
}

fun CtTypeNameModel.toShortString(): Pair<String, String?> {
    return when(this) {
        is ArrayNameModel -> elementType.toShortString().let { it.copy(first = "${it.first}[]") }
        is ClassNameModel -> simpleNames.joinToString(".") to " (${packageName})"
        is InvalidNameModel -> "<INVALID>" to this.toString()
        is KeywordTypeNameModel -> this.name to null
        is ParameterizedNameModel -> raw.toShortString().let {
            it.copy(first = buildString {
                append(it.first)
                append(typeArguments.joinToString(prefix = "<", postfix = ">") { arg -> arg.toShortString().first })
            })
        }
        is WildcardNameModel -> upperBound?.toShortString()?.let {
            it.copy(first = "? <: ${it.first}")
        } ?: lowerBound?.toShortString()?.let {
            it.copy(first = "? >: ${it.first}")
        } ?: ("*" to null)
    }
}

fun CtTypeNameModel.toColoredText(): ColoredText {
    return ColoredText.builder().apply {
        val (mainPart: String, otherPart: String?) = toShortString()
        append(mainPart, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        otherPart?.let {
            append(it, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }.build()
}

private class BindingGraphTreeCellRenderer : ColoredTreeCellRenderer() {
    private fun appendName(name: CtTypeNameModel) {
        append(name.toColoredText())
    }

    private fun appendNodeModel(node: AnalyzedNodeModel) {
        node.qualifier?.let {
            append("[")
            append(it.shortRepr, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.CYAN))
            append("] ")
        }
        appendName(node.typeName)
    }

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val obj = value as? GraphPresentationNode ?: return
        when(val node = obj.userObject) {
            GraphTreeNode.Root -> {
                append("<root>")
            }
            is GraphTreeNode.Label -> {
                icon = null
                val text = when(node) {
                    GraphTreeNode.Label.Modules -> "Modules"
                    GraphTreeNode.Label.ChildComponents -> "Child Components"
                    GraphTreeNode.Label.Bindings -> "Bindings"
                }
                append(text, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(" (${value.childCount})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is GraphTreeNode.Element -> node.data.accept(object : GraphElement.Visitor<Unit> {
                override fun visitGraph(graph: AnalyzedBindingGraph) {
                    appendName(graph.name)
                    icon = Icons.RootComponent
                }

                override fun visitModule(module: AnalyzedModuleModel) {
                    appendName(module.name)
                    icon = if (module.isStatic) Icons.ModuleStatic else Icons.Module
                }

                override fun visitBinding(binding: AnalyzedBindingModel) {
                    appendNodeModel(binding.target)
                    append(" ← ")
                    val kindText = when(binding.kind) {
                        AnalyzedBindingModel.Kind.Provision -> "@Provides"
                        AnalyzedBindingModel.Kind.Inject -> "@Inject constructor"
                        AnalyzedBindingModel.Kind.Alias -> "@Binds (alias)"
                        AnalyzedBindingModel.Kind.Alternatives -> "@Binds (alternatives)"
                        AnalyzedBindingModel.Kind.AssistedInject -> "@AssistedInject"
                        AnalyzedBindingModel.Kind.ComponentDependency -> "Component Dependency (instance)"
                        AnalyzedBindingModel.Kind.ComponentDependencyEp -> "Component Dependency (getter)"
                        AnalyzedBindingModel.Kind.ComponentInstance -> "Component Instance (builtin)"
                        AnalyzedBindingModel.Kind.ExplicitEmpty -> "@Binds (explicit absent)"
                        AnalyzedBindingModel.Kind.Missing -> "Missing binding!"
                        AnalyzedBindingModel.Kind.Instance -> "@BindsInstance"
                        AnalyzedBindingModel.Kind.Map -> "Multibinding (map)"
                        AnalyzedBindingModel.Kind.List -> "Multibinding (list)"
                        AnalyzedBindingModel.Kind.Set -> "Multibinding (set)"
                    }
                    append(kindText, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    binding.label?.let {
                        append(": ")
                        append(it, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    icon = when (binding.kind) {
                        AnalyzedBindingModel.Kind.Provision ->
                            if (binding.isConditional) Icons.ProvisionConditional else Icons.Provision
                        AnalyzedBindingModel.Kind.Inject ->
                            if (binding.isConditional) Icons.InjectConditional else Icons.Inject
                        AnalyzedBindingModel.Kind.Alias -> Icons.Alias
                        AnalyzedBindingModel.Kind.Alternatives -> Icons.Alternatives
                        AnalyzedBindingModel.Kind.AssistedInject ->
                            if (binding.isConditional) Icons.AssistedInjectConditional else Icons.AssistedInject
                        AnalyzedBindingModel.Kind.ComponentDependency,
                        AnalyzedBindingModel.Kind.ComponentDependencyEp -> Icons.Builtin
                        AnalyzedBindingModel.Kind.ComponentInstance -> Icons.RootComponent
                        AnalyzedBindingModel.Kind.ExplicitEmpty -> Icons.ExplicitEmpty
                        AnalyzedBindingModel.Kind.Missing -> Icons.Missing
                        AnalyzedBindingModel.Kind.Instance -> Icons.Instance
                        AnalyzedBindingModel.Kind.Map -> Icons.Map
                        AnalyzedBindingModel.Kind.List -> Icons.List
                        AnalyzedBindingModel.Kind.Set -> Icons.Set
                    }
                }

                override fun visitRegularDependency(dependency: AnalyzedBindingDependencies.Regular.Dependency) {
                    appendNodeModel(dependency.node)
                    icon = when(dependency.kind) {
                        DependencyKind.Direct -> Icons.Direct
                        DependencyKind.Lazy -> Icons.Lazy
                        DependencyKind.Provider -> Icons.Provider
                        // FIXME: Provide icons for optional
                        DependencyKind.Optional -> Icons.Direct
                        DependencyKind.OptionalLazy -> Icons.Lazy
                        DependencyKind.OptionalProvider -> Icons.Provider
                    }
                }

                override fun visitAlternative(alternative: AnalyzedBindingDependencies.Alternatives.Alternative) {
                    appendNodeModel(alternative.node)
                    // TODO: Provide propert icon
                    icon = Icons.Alternatives
                }

                override fun visitMultibindingContribution(contribution: AnalyzedBindingDependencies.Multibinding.Contribution) {
                    append(contribution.label)
                    // TODO: Provide propert icon
                    icon = Icons.List
                }
            })
        }
    }
}

class GraphPresentationNode(
    node: GraphTreeNode,
) : DefaultMutableTreeNode(node, node.allowsChildren()) {

    override fun getUserObject(): GraphTreeNode {
        return super.getUserObject() as GraphTreeNode
    }

    override fun setUserObject(userObject: Any?) {
        require(userObject is GraphTreeNode)
        super.setUserObject(userObject)
    }

    override fun getUserObjectPath(): Array<GraphTreeNode> {
        @Suppress("UNCHECKED_CAST")
        return super.getUserObjectPath() as Array<GraphTreeNode>
    }
}

fun createNodeForGraph(
    bindingGraph: AnalyzedBindingGraph,
    useLabels: Boolean = true,
    flattenModules: Boolean = true,
    groupBindingsByModule: Boolean = false,
): GraphPresentationNode {
    val graphNode = GraphPresentationNode(GraphTreeNode.Element(bindingGraph))

    // TODO: Rewrite here properly!!

    val childrenToDisplay = bindingGraph.children.filter(filter)
    if (childrenToDisplay.isNotEmpty()) {
        val parent = if (useLabels) {
            GraphPresentationNode(GraphTreeNode.Label.ChildComponents).also { graphNode.add(it) }
        } else graphNode
        for (child in childrenToDisplay) {
            parent.add(createNodeForGraph(
                bindingGraph = child,
                filter = filter,
                useLabels = useLabels,
            ))
        }
    }

    val bindingsToDisplay = bindingGraph.bindings.filter(filter)
    val bindings2Module = bindingsToDisplay.groupBy {
        it.module
    }

    val modulesToDisplay = if (flattenModules) {
        bindingGraph.flattenedModules
    } else {
        bindingGraph.immediateModules
    }.filter(filter)

    if (modulesToDisplay.isNotEmpty()) {
        val parentNode = if (useLabels) {
            GraphPresentationNode(GraphTreeNode.Label.Modules).also { graphNode.add(it) }
        } else graphNode
        for (module in modulesToDisplay) {
            fun createModuleNode(parentNode: GraphPresentationNode, moduleModel: AnalyzedModuleModel) {
                val moduleNode = GraphPresentationNode(GraphTreeNode.Element(module))
                if (!flattenModules) for (child in moduleModel.includes) {
                    createModuleNode(moduleNode, child)
                }
                parentNode.add(moduleNode)
                if (groupBindingsByModule) {
                    val parentNode = if (useLabels) {
                        GraphPresentationNode(GraphTreeNode.Label.Bindings).also { moduleNode.add(it) }
                    } else moduleNode

                }
            }
            createModuleNode(parentNode, module)
        }
    }

    val parentNode = if (useLabels) {
        GraphPresentationNode(GraphTreeNode.Label.Bindings).also { graphNode.add(it) }
    } else graphNode


    if (groupBindingsByModule) {
        fun buildModuleNode(module: AnalyzedModuleModel): GraphPresentationNode? {
            val children = module.includes.mapNotNull { buildModuleNode(it) }
            val bindings = bindings2Module[module] ?: emptyList()
            if (children.isEmpty() && bindings.isEmpty())
                return null

            val moduleNode = GraphPresentationNode(GraphTreeNode.Element(module))
            for (binding in bindings) {
                val bindingNode = GraphPresentationNode(GraphTreeNode.Element(binding))
                moduleNode.add(bindingNode)
                when (val dependencies = binding.dependencies) {
                    is AnalyzedBindingDependencies.Alternatives -> for (alternative in dependencies.alternatives) {
                        bindingNode.add(GraphPresentationNode(GraphTreeNode.Element(alternative)))
                    }
                    is AnalyzedBindingDependencies.Multibinding -> for (contribution in dependencies.contributions) {
                        bindingNode.add(GraphPresentationNode(GraphTreeNode.Element(contribution)))
                    }
                    is AnalyzedBindingDependencies.Regular -> for (dependency in dependencies.dependencies) {
                        bindingNode.add(GraphPresentationNode(GraphTreeNode.Element(dependency)))
                    }
                }
            }
            return moduleNode
        }

        for (module in modulesToDisplay) {
            buildModuleNode(module)
        }
    } else {
        for (binding in bindingsToDisplay) {
            // FIXME: No alias bindings here
            val bindingNode = GraphPresentationNode(GraphTreeNode.BindingModel(binding) ?: continue)
            parent.add(bindingNode)
            binding.accept(BindingDependenciesNodeProducer).forEach { bindingNode.add(GraphPresentationNode(it)) }
        }
    }

    return graphNode
}

private object BindingDependenciesNodeProducer : Binding.Visitor<List<GraphTreeNode>> {
    override fun visitOther(binding: Binding) = binding.dependencies.map { dependency ->
        GraphTreeNode.BindingDependency(
            NodeDependencyModel(
                node = dependency.node.toElement(),
                kind = dependency.kind
            )
        )
    }

    override fun visitMap(binding: MapBinding) = binding.contents.map {
        // TODO: Refer to @Multibinds (if the list is empty)
        GraphTreeNode.MultiBindingContribution(
            navigateTo = it.origin.method.platformModel!!.cast<PsiElement>()
                .let(SmartPointerManager::createPointer),
            label = it.origin.method.accept(CallableToShortString)!!,
        )
    }

    override fun visitMulti(binding: MultiBinding) = binding.contributions.map {
        // TODO: Refer to @Multibinds (if the list is empty)
        GraphTreeNode.MultiBindingContribution(
            navigateTo = it.origin.method.platformModel!!.cast<PsiElement>()
                .let(SmartPointerManager::createPointer),
            label = it.origin.method.accept(CallableToShortString)!!,
        )
    }
}