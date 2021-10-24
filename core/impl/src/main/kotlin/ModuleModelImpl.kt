package com.yandex.daggerlite.core.impl

import com.yandex.daggerlite.Binds
import com.yandex.daggerlite.Module
import com.yandex.daggerlite.Provides
import com.yandex.daggerlite.core.AliasBinding
import com.yandex.daggerlite.core.BaseBinding
import com.yandex.daggerlite.core.ComponentModel
import com.yandex.daggerlite.core.ModuleModel
import com.yandex.daggerlite.core.ProvisionBinding
import com.yandex.daggerlite.core.lang.AnnotationLangModel
import com.yandex.daggerlite.core.lang.TypeDeclarationLangModel
import com.yandex.daggerlite.core.lang.TypeLangModel
import com.yandex.daggerlite.core.lang.getAnnotation
import com.yandex.daggerlite.core.lang.hasType
import com.yandex.daggerlite.core.lang.isAnnotatedWith

internal class ModuleModelImpl(
    private val declaration: TypeDeclarationLangModel,
) : ModuleModel() {
    init {
        require(canRepresent(declaration))
    }

    private val impl = declaration.getAnnotation<Module>()

    override val type: TypeLangModel
        get() = declaration.asType()

    @Suppress("UNCHECKED_CAST")
    override val subcomponents: Collection<ComponentModel> by lazy {
        impl.getTypes("subcomponents").map(TypeLangModel::declaration).map(::ComponentModelImpl).toSet()
    }

    override val bindings: Collection<BaseBinding> by lazy {
        val list = arrayListOf<BaseBinding>()
        val mayRequireInstance = !declaration.isAbstract && !declaration.isKotlinObject

        declaration.allPublicFunctions.mapNotNullTo(list) { method ->
            val target = NodeModelImpl(
                type = method.returnType,
                forQualifier = method,
            )
            method.annotations.forEach { ann ->
                when {
                    ann.hasType<Binds>() -> AliasBinding(
                        target = target,
                        source = NodeModelImpl(
                            type = method.parameters.single().type,
                            forQualifier = method.parameters.single(),
                        ),
                    )
                    ann.hasType<Provides>() -> ProvisionBinding(
                        target = target,
                        provider = method,
                        scope = method.annotations.find(AnnotationLangModel::isScope),
                        requiredModuleInstance = this.takeIf { mayRequireInstance && !method.isStatic },
                        params = method.parameters.map { param ->
                            nodeModelDependency(type = param.type, forQualifier = param)
                        }.toList(),
                    )
                    else -> null
                }?.let { return@mapNotNullTo it }
            }
            null
        }
    }

    override val isInstanceRequired: Boolean by lazy {
        !declaration.isAbstract && bindings.any { it is ProvisionBinding && it.requiredModuleInstance != null }
    }

    companion object {
        fun canRepresent(declaration: TypeDeclarationLangModel): Boolean {
            return declaration.isAnnotatedWith<Module>()
        }
    }
}