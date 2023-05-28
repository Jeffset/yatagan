package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiModifierListOwner
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtAnnotationBase

internal class IJAnnotated(
    private val impl: PsiModifierListOwner,
) : CtAnnotated {
    override val annotations: Sequence<CtAnnotationBase>
        get() = impl.annotations.asSequence().map { IJAnnotationImpl(it) }

    override fun <A : Annotation> isAnnotatedWith(type: Class<A>): Boolean {
        return impl.hasAnnotation(type.canonicalName)
    }
}