package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiManager
import com.yandex.yatagan.base.api.Extensible
import com.yandex.yatagan.lang.scope.LexicalScope

internal val LexicalScope.Utils: ProcessingUtils get() = ext[ProcessingUtils]

class ProcessingUtils(
    val project: Project,
) {
    val psiManager: PsiManager = PsiManager.getInstance(this.project)
    val factory: PsiElementFactory = PsiElementFactory.getInstance(this.project)

    companion object : Extensible.Key<ProcessingUtils, LexicalScope.Extensions>
}