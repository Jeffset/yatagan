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

@file:Suppress("NOTHING_TO_INLINE")

package com.yandex.yatagan.lang

import com.yandex.yatagan.lang.scope.LexicalScope

/**
 * An accessor for the [LangModelFactory] tied to the lexical scope.
 */
public val LexicalScope.Extensions.langFactory: LangModelFactory
    get() = get(LangModelFactory)

public val TypeDeclaration.functionsWithCompanion: Sequence<Method>
    get() = when (val companion = defaultCompanionObjectDeclaration) {
        null -> methods
        else -> methods + companion.methods
    }

public inline fun LangModelFactory.getListType(parameter: Type, isCovariant: Boolean = false): Type {
    return getParameterizedType(
        type = LangModelFactory.ParameterizedType.List,
        parameter = parameter,
        isCovariant = isCovariant,
    )
}

public inline fun LangModelFactory.getSetType(parameter: Type, isCovariant: Boolean = false): Type {
    return getParameterizedType(
        type = LangModelFactory.ParameterizedType.Set,
        parameter = parameter,
        isCovariant = isCovariant,
    )
}

public inline fun LangModelFactory.getCollectionType(parameter: Type, isCovariant: Boolean = false): Type {
    return getParameterizedType(
        type = LangModelFactory.ParameterizedType.Collection,
        parameter = parameter,
        isCovariant = isCovariant,
    )
}

public inline fun LangModelFactory.getProviderType(parameter: Type, isCovariant: Boolean = false): Type {
    return getParameterizedType(
        type = LangModelFactory.ParameterizedType.Provider,
        parameter = parameter,
        isCovariant = isCovariant,
    )
}

public operator fun Member.compareTo(other: Member): Int {
    return MemberComparator.compare(this, other)
}

public object MemberComparator : Comparator<Member> {
    override fun compare(one: Member, other: Member): Int = one.accept(object : Member.Visitor<Int> {
        override fun visitOther(model: Member) = throw AssertionError()
        override fun visitMethod(model: Method): Int {
            val thisMethod = model
            return other.accept(object : Member.Visitor<Int> {
                override fun visitOther(model: Member) = throw AssertionError()
                override fun visitMethod(model: Method) = thisMethod.compareTo(model)
                override fun visitField(model: Field) = +1  // Method is greater than field by convention
            })
        }

        override fun visitField(model: Field): Int {
            val thisField = model
            return other.accept(object : Member.Visitor<Int> {
                override fun visitOther(model: Member) = throw AssertionError()
                override fun visitMethod(model: Method) = -1  // Field is lesser than method by convention
                override fun visitField(model: Field) = thisField.compareTo(model)
            })
        }
    })
}