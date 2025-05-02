package com.yandex.yatagan.intellij.data

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.yandex.yatagan.base.api.Incubating
import com.yandex.yatagan.base.ifOrElseNull
import com.yandex.yatagan.core.graph.bindings.AlternativesBinding
import com.yandex.yatagan.core.graph.bindings.AssistedInjectFactoryBinding
import com.yandex.yatagan.core.graph.bindings.Binding
import com.yandex.yatagan.core.graph.bindings.ComponentDependencyBinding
import com.yandex.yatagan.core.graph.bindings.ComponentDependencyEntryPointBinding
import com.yandex.yatagan.core.graph.bindings.ComponentInstanceBinding
import com.yandex.yatagan.core.graph.bindings.ConditionExpressionValueBinding
import com.yandex.yatagan.core.graph.bindings.EmptyBinding
import com.yandex.yatagan.core.graph.bindings.InstanceBinding
import com.yandex.yatagan.core.graph.bindings.MapBinding
import com.yandex.yatagan.core.graph.bindings.MultiBinding
import com.yandex.yatagan.core.graph.bindings.ProvisionBinding
import com.yandex.yatagan.core.graph.bindings.SubComponentBinding
import com.yandex.yatagan.core.model.CollectionTargetKind
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.intellij.asSmartPointer
import com.yandex.yatagan.intellij.getPsiElement
import com.yandex.yatagan.intellij.getSourceSmartPointer
import com.yandex.yatagan.intellij.resolveBindingOrNull

@OptIn(Incubating::class)
class AnalyzedBindingModel(
    val target: AnalyzedNodeModel,
    val kind: Kind,
    val label: String?,
    val isConditional: Boolean,
    val dependencies: AnalyzedBindingDependencies,
    val module: AnalyzedModuleModel?,
    override val source: SmartPsiElementPointer<PsiElement>?,
) : GraphElement {
    override val id: Any = source ?: target

    constructor(
        binding: Binding,
        modules: AnalyzedModelMap<ModuleModel, AnalyzedModuleModel>,
    ) : this(
        kind = binding.accept(GetBindingKind),
        target = AnalyzedNodeModel(binding.target),
        isConditional = !binding.conditionScope.isTautology(),
        source = binding.accept(GetBindingSource),
        label = binding.accept(GetBindingLabel),
        dependencies = binding.accept(GetBindingDependencies),
        module = binding.originModule?.let(modules::get)
    )

    override fun <R> accept(visitor: GraphElement.Visitor<R>): R {
        return visitor.visitBinding(this)
    }

    enum class Kind {
        Provision,
        Inject,
        Alias,
        Alternatives,
        AssistedInject,
        ComponentDependency,
        ComponentDependencyEp,
        ComponentInstance,
        ExplicitEmpty,
        Missing,
        Instance,
        Map,
        List,
        Set,
    }

    private object GetBindingKind : Binding.Visitor<Kind> {
        override fun visitOther(binding: Binding) = throw AssertionError()
        override fun visitProvision(binding: ProvisionBinding) =
            if (binding.isInjectConstructor) Kind.Inject else Kind.Provision
        override fun visitAssistedInjectFactory(binding: AssistedInjectFactoryBinding) = Kind.AssistedInject
        override fun visitInstance(binding: InstanceBinding) = Kind.Instance
        override fun visitAlternatives(binding: AlternativesBinding) = Kind.Alternatives
        override fun visitSubComponent(binding: SubComponentBinding) = Kind.Missing
        override fun visitComponentDependency(binding: ComponentDependencyBinding) = Kind.ComponentDependency
        override fun visitComponentInstance(binding: ComponentInstanceBinding) = Kind.ComponentInstance
        override fun visitComponentDependencyEntryPoint(binding: ComponentDependencyEntryPointBinding) =
            Kind.ComponentDependencyEp
        override fun visitMulti(binding: MultiBinding) = when(binding.kind) {
            CollectionTargetKind.List -> Kind.List
            CollectionTargetKind.Set -> Kind.Set
        }
        override fun visitMap(binding: MapBinding) = Kind.Map
        override fun visitConditionExpressionValue(binding: ConditionExpressionValueBinding) = Kind.Missing
        override fun visitEmpty(binding: EmptyBinding): Kind =
            if (binding.isUnresolved) Kind.Missing else Kind.ExplicitEmpty
    }

    private object GetBindingSource : Binding.Visitor<SmartPsiElementPointer<PsiElement>?> {
        override fun visitOther(binding: Binding) = null
        override fun visitProvision(binding: ProvisionBinding) =
            binding.provision.getPsiElement()?.asSmartPointer()
        override fun visitAssistedInjectFactory(binding: AssistedInjectFactoryBinding) =
            binding.model.getSourceSmartPointer()
        override fun visitInstance(binding: InstanceBinding) = binding.origin.getPsiElement()?.asSmartPointer()
        override fun visitAlternatives(binding: AlternativesBinding) =
            binding.methodModel?.method?.getPsiElement()?.asSmartPointer()
        override fun visitSubComponent(binding: SubComponentBinding) = null
        override fun visitComponentDependency(binding: ComponentDependencyBinding) =
            binding.owner.model.getSourceSmartPointer()  // TODO: Maybe navigate to Dep declaration?
        override fun visitComponentInstance(binding: ComponentInstanceBinding) =
            binding.owner.model.getSourceSmartPointer()
        override fun visitComponentDependencyEntryPoint(binding: ComponentDependencyEntryPointBinding) =
            binding.getter.getPsiElement()?.asSmartPointer()
        override fun visitMulti(binding: MultiBinding) = null
        override fun visitMap(binding: MapBinding) = null
        override fun visitConditionExpressionValue(binding: ConditionExpressionValueBinding) = null
        override fun visitEmpty(binding: EmptyBinding) = binding.methodModel?.method?.getPsiElement()?.asSmartPointer()
    }

    private object GetBindingLabel : Binding.Visitor<String?> {
        override fun visitOther(binding: Binding) = binding.methodModel?.method?.toShortString()
        override fun visitProvision(binding: ProvisionBinding) =
            ifOrElseNull(!binding.isInjectConstructor) { binding.methodModel?.method?.toShortString() }
        override fun visitComponentDependencyEntryPoint(binding: ComponentDependencyEntryPointBinding) =
            binding.getter.toShortString()
    }

    private object GetBindingDependencies : Binding.Visitor<AnalyzedBindingDependencies> {
        override fun visitOther(binding: Binding) = AnalyzedBindingDependencies.Regular(
            dependencies = binding.dependencies.map { dependency ->
                AnalyzedBindingDependencies.Regular.Dependency(
                    node = AnalyzedNodeModel(dependency.node),
                    kind = dependency.kind,
                )
            },
        )

        override fun visitAlternatives(binding: AlternativesBinding) = AnalyzedBindingDependencies.Alternatives(
            alternatives = binding.alternatives.mapIndexed { index, node ->
                AnalyzedBindingDependencies.Alternatives.Alternative(
                    index = index,
                    node = AnalyzedNodeModel(node),
                    condition = AnalyzedConditionScope(binding.owner.resolveBindingOrNull(node)?.conditionScope)
                )
            }
        )

        override fun visitMulti(binding: MultiBinding) = AnalyzedBindingDependencies.Multibinding(
            kind = when(binding.kind) {
                CollectionTargetKind.List -> AnalyzedBindingDependencies.Multibinding.Kind.List
                CollectionTargetKind.Set -> AnalyzedBindingDependencies.Multibinding.Kind.Set
            },
            contributions = binding.contributions.map {
                AnalyzedBindingDependencies.Multibinding.Contribution(
                    label = it.origin.method.toShortString(),
                    source = it.origin.method.getPsiElement()!!.asSmartPointer(),
                )
            }
        )

        override fun visitMap(binding: MapBinding) = AnalyzedBindingDependencies.Multibinding(
            kind = AnalyzedBindingDependencies.Multibinding.Kind.Map,
            contributions = binding.contents.map {
                AnalyzedBindingDependencies.Multibinding.Contribution(
                    label = it.origin.method.toShortString(),
                    source = it.origin.method.getPsiElement()!!.asSmartPointer(),
                )
            }
        )
    }
}