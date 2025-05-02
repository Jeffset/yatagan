package com.yandex.yatagan.intellij.data

import com.yandex.yatagan.lang.Method

internal fun Method.toShortString(): String = buildString {
    // FIXME: Use name model instead of qualifiedName
    append(owner.qualifiedName.substringAfterLast('.')).append("::").append(name).append('(')
    if (parameters.any()) append("...")
    append(')')
}