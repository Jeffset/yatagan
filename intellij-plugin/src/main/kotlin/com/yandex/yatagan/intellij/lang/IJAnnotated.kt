package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiModifierListOwner
import com.yandex.yatagan.base.memoize
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtAnnotationBase
import com.yandex.yatagan.lang.scope.LexicalScope
import org.jetbrains.uast.UAnnotated

internal class IJAnnotated<E : PsiModifierListOwner>(
    private val lexicalScope: LexicalScope,
    element: E,
    toUast: (E) -> UAnnotated,
    tracker: DependenciesTracker,
) : CtAnnotated {

    override val annotations: Sequence<CtAnnotationBase> by lazy {
        if (element.isFromJava()) {
            // Use psi as is
            element.annotations.asSequence().map {
                lexicalScope.IJAnnotation(it, tracker)
            }.memoize()
        } else {
            toUast(element).uAnnotations.asSequence().map {
                lexicalScope.IJAnnotation(it, tracker)
            }.memoize()
        }
    }
}