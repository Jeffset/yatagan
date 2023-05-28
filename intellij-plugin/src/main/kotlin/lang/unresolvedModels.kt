package com.yandex.yatagan.intellij.lang

import com.yandex.yatagan.lang.Annotation
import com.yandex.yatagan.lang.common.AnnotationBase
import com.yandex.yatagan.lang.compiled.CtAnnotationDeclarationBase

internal class RemovedPsiError : RuntimeException()

internal class UnresolvedAnnotationClass(
    override val qualifiedName: String,
) : CtAnnotationDeclarationBase() {
    override val annotations get() = emptySequence<Nothing>()
    override val attributes get() = emptySequence<Nothing>()
    override fun getRetention() = AnnotationRetention.RUNTIME
}

internal class UnresolvedValue : AnnotationBase.ValueBase() {
    override fun <R> accept(visitor: Annotation.Value.Visitor<R>) = visitor.visitUnresolved()
    override val platformModel get() = null
}