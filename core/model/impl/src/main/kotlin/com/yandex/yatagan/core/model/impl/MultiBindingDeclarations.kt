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

import com.yandex.yatagan.base.setOf
import com.yandex.yatagan.core.model.CollectionTargetKind
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.core.model.MultiBindingDeclarationModel
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.lang.HasPlatformModel
import com.yandex.yatagan.lang.Method
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.validation.MayBeInvalid
import com.yandex.yatagan.validation.Validator
import com.yandex.yatagan.validation.format.Strings
import com.yandex.yatagan.validation.format.TextColor
import com.yandex.yatagan.validation.format.append
import com.yandex.yatagan.validation.format.modelRepresentation
import com.yandex.yatagan.validation.format.reportError
import kotlin.LazyThreadSafetyMode.PUBLICATION

internal abstract class MultiBindingDeclarationBase(
    override val owner: ModuleModel,
    override val method: Method,
) : MultiBindingDeclarationModel {
    override fun validate(validator: Validator) {
        if (!method.isAbstract || method.parameters.any()) {
            validator.reportError(Strings.Errors.invalidMultiBindingDeclaration()) {
                addNote(Strings.Notes.invalidMultiBindingAdvice())
            }
        }
    }

    override val langModel: HasPlatformModel
        get() = method
}

internal class CollectionDeclarationImpl(
    owner: ModuleModel,
    method: Method,
) : MultiBindingDeclarationBase(owner, method), MultiBindingDeclarationModel.CollectionDeclarationModel {
    init {
        assert(canRepresent(method))
    }

    override val elementType: NodeModel?
        get() = method.returnType.typeArguments.firstOrNull()?.let { type ->
            NodeModelImpl(
                type = type,
                forQualifier = method,
            )
        }

    override fun <R> accept(visitor: MultiBindingDeclarationModel.Visitor<R>): R {
        return visitor.visitCollectionDeclaration(this)
    }

    override fun validate(validator: Validator) {
        super.validate(validator)
        if (method.returnType.typeArguments.isEmpty()) {
            validator.reportError(Strings.Errors.invalidMultiBindingDeclaration()) {
                addNote(Strings.Notes.invalidMultiBindingAdvice())
            }
        }
    }

    override fun toString(childContext: MayBeInvalid?): CharSequence {
        val type = when (kind) {
            CollectionTargetKind.List -> "list"
            CollectionTargetKind.Set -> "set"
        }
        return modelRepresentation(modelClassName = "multibinding declaration ($type)") {
            append(method)
        }
    }

    override val kind: CollectionTargetKind by lazy(PUBLICATION) {
        when (method.returnType.declaration.qualifiedName) {
            Names.List -> CollectionTargetKind.List
            Names.Set -> CollectionTargetKind.Set
            else -> throw AssertionError("Not reached")
        }
    }

    override fun hashCode(): Int {
        var result = elementType?.hashCode() ?: 0
        result = 31 * result + kind.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is CollectionDeclarationImpl &&
                kind == other.kind &&
                elementType == other.elementType)
    }

    companion object {
        private val SupportedCollectionNames = setOf(Names.List, Names.Set)

        fun canRepresent(method: Method): Boolean {
            return method.returnType.declaration.qualifiedName in SupportedCollectionNames
        }
    }
}

internal class MapDeclarationImpl(
    owner: ModuleModel,
    method: Method,
) : MultiBindingDeclarationBase(owner, method), MultiBindingDeclarationModel.MapDeclarationModel {
    init {
        assert(canRepresent(method))
    }

    override fun <R> accept(visitor: MultiBindingDeclarationModel.Visitor<R>): R {
        return visitor.visitMapDeclaration(this)
    }

    override val keyType: Type?
        get() = method.returnType.typeArguments.firstOrNull()

    override val valueType: NodeModel?
        get() = method.returnType.typeArguments.getOrNull(1)?.let { type ->
            NodeModelImpl(
                type = type,
                forQualifier = method,
            )
        }

    override fun validate(validator: Validator) {
        super.validate(validator)
        if (method.returnType.typeArguments.size != 2) {
            validator.reportError(Strings.Errors.invalidMultiBindingDeclaration()) {
                addNote(Strings.Notes.invalidMultiBindingAdvice())
            }
        }
    }

    override fun toString(childContext: MayBeInvalid?): CharSequence {
        return modelRepresentation(modelClassName = "multibinding declaration (map)") {
            append(method)
        }
    }

    override fun hashCode() = 31 * keyType.hashCode() + valueType.hashCode()
    override fun equals(other: Any?) = this === other || (other is MapDeclarationImpl &&
            keyType == other.keyType && valueType == other.valueType)

    companion object {
        fun canRepresent(method: Method): Boolean {
            return method.returnType.declaration.qualifiedName == Names.Map
        }
    }
}

internal class InvalidDeclarationImpl(
    override val owner: ModuleModel,
    override val method: Method,
) : MultiBindingDeclarationModel.InvalidDeclarationModel {
    override fun <R> accept(visitor: MultiBindingDeclarationModel.Visitor<R>): R {
        return visitor.visitInvalid(this)
    }

    override fun hashCode(): Int = method.hashCode()
    override fun equals(other: Any?) = this === other || (other is InvalidDeclarationImpl &&
            method == other.method)

    override fun validate(validator: Validator) {
        validator.reportError(Strings.Errors.invalidMultiBindingDeclaration()) {
            addNote(Strings.Notes.invalidMultiBindingAdvice())
        }
    }

    override fun toString(childContext: MayBeInvalid?): CharSequence {
        return modelRepresentation(modelClassName = "multibinding declaration") {
            color = TextColor.Red
            append("invalid `").append(method).append('`')
        }
    }

    override val langModel: HasPlatformModel
        get() = method
}