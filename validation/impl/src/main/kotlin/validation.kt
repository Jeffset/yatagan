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

package com.yandex.yatagan.validation.impl

import com.yandex.yatagan.base.CharSequenceComparator
import com.yandex.yatagan.base.traverseDepthFirstWithPath
import com.yandex.yatagan.core.model.ClassBackedModel
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.validation.LocatedMessage
import com.yandex.yatagan.validation.MayBeInvalid
import com.yandex.yatagan.validation.ValidationMessage
import com.yandex.yatagan.validation.Validator
import com.yandex.yatagan.validation.format.Strings
import com.yandex.yatagan.validation.format.reportError
import kotlin.LazyThreadSafetyMode.NONE

private class ValidatorImpl : Validator {
    private val _children = arrayListOf<MayBeInvalid>()
    val children: List<MayBeInvalid>
        get() = _children
    private val _messages = lazy(NONE) { arrayListOf<ValidationMessage>() }
    val messages: List<ValidationMessage>
        get() = if (_messages.isInitialized()) _messages.value else emptyList()

    override fun report(message: ValidationMessage) {
        _messages.value += message
    }

    override fun child(node: MayBeInvalid) {
        _children += node
    }

    override fun inline(node: MayBeInvalid) {
        if (node is ClassBackedModel) {
            val type = node.type
            // A uniform mechanism of reporting unresolved types.
            if (type.isInvalid()) {
                reportError(Strings.Errors.invalidType(type)) {
                    if ("unresolved-type-var" in type.toString()) {
                        addNote(Strings.Notes.unresolvedTypeVar())
                    } else {
                        addNote(Strings.Notes.whyTypeCanBeUnresolved())
                    }
                }
            }
        }
        node.validate(this)
    }
}

fun validate(
    root: MayBeInvalid,
): Collection<LocatedMessage> {
    val cache = hashMapOf<MayBeInvalid, ValidatorImpl>()
    val result: MutableMap<ValidationMessage, MutableSet<List<MayBeInvalid>>> = mutableMapOf()

    traverseDepthFirstWithPath(
        roots = listOf(root),
        childrenOf = { cache[it]?.children ?: emptyList() },
        visit = { path, node ->
            val validator = cache.getOrPut(node) {
                ValidatorImpl().also { validator -> validator.inline(node) }
            }
            for (message in validator.messages) {
                // Extract current path from stack::substack
                result.getOrPut(message, ::mutableSetOf) += path.toList()
            }
        }
    )

    return result.map { (message, paths) ->
        LocatedMessage(
            message = object : ValidationMessage by message {
                override val notes: Collection<CharSequence> = message.notes.sortedWith(CharSequenceComparator)
            },
            encounterPaths = paths.toList(),
        )
    }
}

private fun Type.isInvalid(): Boolean {
    return isUnresolved || typeArguments.any(Type::isInvalid)
}