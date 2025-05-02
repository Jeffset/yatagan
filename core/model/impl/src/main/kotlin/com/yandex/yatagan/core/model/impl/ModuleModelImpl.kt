/*
 * Copyright 2022 Yandex LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yandex.yatagan.core.model.impl

import com.yandex.yatagan.base.memoize
import com.yandex.yatagan.core.model.BindsBindingModel
import com.yandex.yatagan.core.model.ComponentModel
import com.yandex.yatagan.core.model.ModuleHostedBindingModel
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.core.model.MultiBindingDeclarationModel
import com.yandex.yatagan.core.model.ProvidesBindingModel
import com.yandex.yatagan.lang.BuiltinAnnotation
import com.yandex.yatagan.lang.HasPlatformModel
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.functionsWithCompanion
import com.yandex.yatagan.lang.scope.FactoryKey
import com.yandex.yatagan.lang.scope.LexicalScope
import com.yandex.yatagan.lang.scope.caching
import com.yandex.yatagan.lang.scope.invoke
import com.yandex.yatagan.validation.MayBeInvalid
import com.yandex.yatagan.validation.Validator
import com.yandex.yatagan.validation.format.Strings
import com.yandex.yatagan.validation.format.modelRepresentation
import com.yandex.yatagan.validation.format.reportError
import kotlin.LazyThreadSafetyMode.PUBLICATION

internal class ModuleModelImpl private constructor(
    private val declaration: TypeDeclaration,
) : ModuleModel {
    private val impl = declaration.getAnnotation(BuiltinAnnotation.Module)

    override val type: Type
        get() = declaration.asType()

    override val includes: Collection<ModuleModel> by lazy {
        impl?.includes?.map(Type::declaration)?.map { Factory(it) }?.toSet() ?: emptySet()
    }

    override val subcomponents: Collection<ComponentModel> by lazy {
        impl?.subcomponents?.map(Type::declaration)?.map { ComponentModelImpl(it) }?.toSet() ?: emptySet()
    }

    override val multiBindingDeclarations: Sequence<MultiBindingDeclarationModel> =
        declaration.methods
            .filter { it.getAnnotation(BuiltinAnnotation.Multibinds) != null }
            .map { method ->
                when {
                    CollectionDeclarationImpl.canRepresent(method) -> CollectionDeclarationImpl(this, method)
                    MapDeclarationImpl.canRepresent(method) -> MapDeclarationImpl(this, method)
                    else -> InvalidDeclarationImpl(this, method)
                }
            }.memoize()

    override val requiresInstance: Boolean by lazy {
        mayRequireInstance && bindings.any { it.accept(AsProvides)?.requiresModuleInstance == true }
    }

    override val isTriviallyConstructable: Boolean
        get() = mayRequireInstance && declaration.constructors.any { it.isEffectivelyPublic && it.parameters.none() }

    override val bindings: Sequence<ModuleHostedBindingModel> = declaration.functionsWithCompanion.mapNotNull { method ->
        when {
            BindsImpl.canRepresent(method) -> BindsImpl(
                method = method,
                originModule = this@ModuleModelImpl,
            )
            ProvidesImpl.canRepresent(method) -> ProvidesImpl(
                method = method,
                originModule = this@ModuleModelImpl,
            )
            else -> null
        }
    }.memoize()

    override fun toString(childContext: MayBeInvalid?) = modelRepresentation(
        modelClassName = "module",
        representation = declaration,
    )

    internal val mayRequireInstance by lazy(PUBLICATION) {
        !declaration.isAbstract
    }

    override fun validate(validator: Validator) {
        if (impl == null) {
            validator.reportError(Strings.Errors.nonModule())
        }
        for (binding in bindings) {
            validator.child(binding)
        }
        for (module in includes) {
            validator.child(module)
        }
        if (!declaration.isEffectivelyPublic && bindings.any { it.accept(AsProvides) != null }) {
            validator.reportError(Strings.Errors.invalidAccessForModuleClass())
        }
        for (declaration in multiBindingDeclarations) {
            validator.child(declaration)
        }
    }

    override val langModel: HasPlatformModel
        get() = declaration

    private object AsProvides : ModuleHostedBindingModel.Visitor<ProvidesBindingModel?> {
        override fun visitOther(model: ModuleHostedBindingModel) = throw AssertionError()
        override fun visitBinds(model: BindsBindingModel) = null
        override fun visitProvides(model: ProvidesBindingModel) = model
    }

    companion object Factory : FactoryKey<TypeDeclaration, ModuleModelImpl> {
        override fun LexicalScope.factory() = caching(::ModuleModelImpl)

        fun canRepresent(declaration: TypeDeclaration): Boolean {
            return declaration.getAnnotation(BuiltinAnnotation.Module) != null
        }
    }
}

