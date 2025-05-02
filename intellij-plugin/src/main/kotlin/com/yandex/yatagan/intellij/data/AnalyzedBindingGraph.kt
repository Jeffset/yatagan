package com.yandex.yatagan.intellij.data

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.model.ClassBackedModel
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.intellij.asSmartPointer
import com.yandex.yatagan.intellij.lang.extra.AnalysisScope
import com.yandex.yatagan.intellij.lang.extra.ComponentModelRef
import com.yandex.yatagan.intellij.name
import com.yandex.yatagan.lang.compiled.CtTypeNameModel

interface GraphElementBase {
    /**
     * A key that hints the identity of the node in the tree.
     * Used for expansion/selection/scroll preservation across tree rebuilds
     */
    val id: Any?

    /**
     * A source construct backing this node, if any.
     * Used for navigation.
     */
    val source: SmartPsiElementPointer<PsiElement>?
}

interface GraphElement : GraphElementBase {
    fun <R> accept(visitor: Visitor<R>): R

    interface Visitor<R> {
        fun visitGraph(graph: AnalyzedBindingGraph): R
        fun visitModule(module: AnalyzedModuleModel): R
        fun visitBinding(binding: AnalyzedBindingModel): R
        fun visitRegularDependency(dependency: AnalyzedBindingDependencies.Regular.Dependency): R
        fun visitAlternative(alternative: AnalyzedBindingDependencies.Alternatives.Alternative): R
        fun visitMultibindingContribution(contribution: AnalyzedBindingDependencies.Multibinding.Contribution): R
    }
}

interface NamedGraphElement : GraphElementBase {
    val name: CtTypeNameModel

    override val id: String
        get() = name.toString()
}

class NamedGraphElementData(
    override val source: SmartPsiElementPointer<PsiElement>?,
    override val name: CtTypeNameModel,
) : NamedGraphElement

fun AnalysisScope.NamedGraphElementData(model: ClassBackedModel): NamedGraphElementData {
    val element = model.type.declaration.platformModel as? PsiClass
    return NamedGraphElementData(
        source = (element?.nameIdentifier ?: element?.lBrace)?.asSmartPointer(),
        name = model.name(),
    )
}

class AnalyzedBindingGraph(
    val rootComponentRef: ComponentModelRef,
    val immediateModules: List<AnalyzedModuleModel>,
    val flattenedModules: List<AnalyzedModuleModel>,
    val bindings: List<AnalyzedBindingModel>,
    val children: List<AnalyzedBindingGraph>,
    data: NamedGraphElementData,
) : NamedGraphElement by data, GraphElement {
    override fun <R> accept(visitor: GraphElement.Visitor<R>): R {
        return visitor.visitGraph(this)
    }
}

fun AnalysisScope.AnalyzedBindingGraph(
    graph: BindingGraph,
    analyzedModules: AnalyzedModelMap<ModuleModel, AnalyzedModuleModel>? = null,
): AnalyzedBindingGraph {
    // FIXME: share the map with the children
    @Suppress("NAME_SHADOWING")
    val analyzedModules = analyzedModules ?: object : AnalyzedModelMap<ModuleModel, AnalyzedModuleModel>() {
        override fun create(source: ModuleModel) = AnalyzedModuleModel(
            includes = source.includes.map(::get),
            data = NamedGraphElementData(source),
            isStatic = !source.requiresInstance,
        )
    }

    return AnalyzedBindingGraph(
        rootComponentRef = graph.model.asRef(),
        data = NamedGraphElementData(graph.model),
        immediateModules = graph.model.modules.map { analyzedModules[it] },
        flattenedModules = graph.modules.map { analyzedModules[it] },
        bindings = graph.localBindings.keys.map { AnalyzedBindingModel(it, analyzedModules) },
        children = graph.children.map { AnalyzedBindingGraph(it, analyzedModules) }
    )
}

abstract class AnalyzedModelMap<S, T> {
    private val map = hashMapOf<S, T>()

    protected abstract fun create(source: S): T

    operator fun get(source: S): T = map.getOrPut(source) { create(source) }
}

class AnalyzedModuleModel(
    val includes: List<AnalyzedModuleModel>,
    val isStatic: Boolean,
    data: NamedGraphElementData,
) : NamedGraphElement by data, GraphElement {
    override fun <R> accept(visitor: GraphElement.Visitor<R>): R {
        return visitor.visitModule(this)
    }
}

data class AnalyzedNodeModel(
    val typeName: CtTypeNameModel,
    val qualifier: AnalyzedAnnotation?,
) {
    constructor(nodeModel: NodeModel) : this(
        typeName = nodeModel.name(),
        qualifier = nodeModel.qualifier?.let(::AnalyzedAnnotation),
    )
}

