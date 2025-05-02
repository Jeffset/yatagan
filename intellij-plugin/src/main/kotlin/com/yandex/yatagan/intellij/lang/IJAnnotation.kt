package com.yandex.yatagan.intellij.lang

import com.yandex.yatagan.lang.compiled.CtAnnotationBase

internal abstract class IJAnnotation : CtAnnotationBase() {
    final override fun hashCode(): Int {
        return cachedStringRepresentation.hashCode()
    }

    final override fun equals(other: Any?): Boolean {
        return this === other || (other is IJAnnotation &&
                cachedStringRepresentation == other.cachedStringRepresentation)
    }
    private val cachedStringRepresentation by plainLazy { toString() }
}