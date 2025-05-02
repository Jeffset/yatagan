package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.yandex.yatagan.lang.Type

internal interface HasDependencies {
    val dependencies: List<Dependency>
}

internal sealed interface Dependency {
    class Psi(val psi: PsiFile) : Dependency
    data object World : Dependency
}

internal class DependenciesTracker : HasDependencies {
    private val psiDependencies = arrayListOf<PsiFile>()
    // TODO: Implement some sort of guard against the dependency loops
    private val references = mutableSetOf<HasDependencies>()

    override val dependencies: List<Dependency>
        get() = buildList {
            for (dep in psiDependencies) add(Dependency.Psi(dep))
            for (ref in references) addAll(ref.dependencies)
        }

    fun track(psi: PsiElement) {
        psiDependencies += psi.containingFile
    }

    fun track(another: HasDependencies) {
        references += another
    }
}

internal object UnresolvedDependencies : HasDependencies {
    override val dependencies: List<Dependency>
        get() = listOf(Dependency.World)
}

internal fun DependenciesTracker.track(type: Type) {
    when(type) {
        is IJTypeImpl -> track(type)
        else -> track(UnresolvedDependencies)
    }
}