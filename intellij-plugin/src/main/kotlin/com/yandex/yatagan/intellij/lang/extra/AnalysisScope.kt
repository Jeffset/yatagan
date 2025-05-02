package com.yandex.yatagan.intellij.lang.extra

import com.intellij.psi.PsiClass
import com.yandex.yatagan.core.model.ComponentModel
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.scope.LexicalScope

interface AnalysisScope {
    val lexicalScope: LexicalScope

    fun getTypeDeclaration(element: PsiClass): TypeDeclaration

    fun ComponentModelRef.get(): ComponentModel?

    fun ComponentModel.asRef(): ComponentModelRef
}