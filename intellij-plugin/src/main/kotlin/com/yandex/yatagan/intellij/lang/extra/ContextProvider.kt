package com.yandex.yatagan.intellij.lang.extra

import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.yandex.yatagan.base.api.Extensible
import com.yandex.yatagan.lang.scope.LexicalScope

interface ContextProvider {
    val context: PsiElement

    companion object : Extensible.Key<ContextProvider, LexicalScope.Extensions>
}

val LexicalScope.localResolveScope: GlobalSearchScope
    get() = ext[ContextProvider].context.resolveScope