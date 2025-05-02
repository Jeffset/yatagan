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

package com.yandex.yatagan.lang.jap

import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtParameterBase
import com.yandex.yatagan.lang.scope.LexicalScope
import javax.lang.model.element.VariableElement
import javax.lang.model.type.TypeMirror
import kotlin.LazyThreadSafetyMode.PUBLICATION

internal class JavaxParameterImpl(
    lexicalScope: LexicalScope,
    override val platformModel: VariableElement,
    refinedType: TypeMirror,
) : CtParameterBase(), CtAnnotated by JavaxAnnotatedImpl(lexicalScope, platformModel), LexicalScope by lexicalScope {
    override val name: String get() = platformModel.simpleName.toString()
    override val type: Type by lazy(PUBLICATION) { JavaxTypeImpl(refinedType) }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is JavaxParameterImpl && platformModel == other.platformModel)
    }

    override fun hashCode() = platformModel.hashCode()
}