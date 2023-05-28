package com.yandex.yatagan.intellij.lang

import com.yandex.yatagan.lang.compiled.CtAnnotationBase

internal abstract class IJAnnotationBase : CtAnnotationBase() {
    final override fun hashCode(): Int {
        return cachedStringRepresentation.hashCode()
    }

    final override fun equals(other: Any?): Boolean {
        return this === other || (other is IJAnnotationBase &&
                cachedStringRepresentation == other.cachedStringRepresentation)
    }
    private val cachedStringRepresentation by plainLazy { toString() }
}