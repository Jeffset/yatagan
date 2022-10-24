package com.yandex.daggerlite.core.graph.impl.bindings

import com.yandex.daggerlite.core.graph.BindingGraph
import com.yandex.daggerlite.core.graph.bindings.Binding
import com.yandex.daggerlite.core.graph.bindings.ProvisionBinding
import com.yandex.daggerlite.core.graph.impl.NonStaticConditionDependencies
import com.yandex.daggerlite.core.graph.impl.VariantMatch
import com.yandex.daggerlite.core.model.ProvidesBindingModel
import com.yandex.daggerlite.core.model.isNever
import com.yandex.daggerlite.validation.MayBeInvalid
import com.yandex.daggerlite.validation.format.append
import com.yandex.daggerlite.validation.format.bindingModelRepresentation

internal class ProvisionBindingImpl(
    override val impl: ProvidesBindingModel,
    override val owner: BindingGraph,
) : ProvisionBinding, ConditionalBindingMixin, ModuleHostedBindingMixin() {

    override val scopes get() = impl.scopes
    override val provision get() = impl.method
    override val inputs get() = impl.inputs
    override val requiresModuleInstance get() = impl.requiresModuleInstance
    override val variantMatch: VariantMatch by lazy { VariantMatch(impl, owner.variant) }

    override val dependencies by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (conditionScope.isNever) emptySequence() else inputs.asSequence()
    }

    override val nonStaticConditionDependencies by lazy {
        NonStaticConditionDependencies(this@ProvisionBindingImpl)
    }

    override fun toString(childContext: MayBeInvalid?) = bindingModelRepresentation(
        modelClassName = "provision",
        childContext = childContext,
        representation = {
            append(impl.originModule.type)
            append("::")
            append(impl.method.name)
        },
    )

    override val checkDependenciesConditionScope get() = true

    override fun <R> accept(visitor: Binding.Visitor<R>): R {
        return visitor.visitProvision(this)
    }
}