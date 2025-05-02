package com.yandex.yatagan.intellij.data

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.yandex.yatagan.core.model.DependencyKind

sealed interface AnalyzedBindingDependencies {
    class Regular(
        val dependencies: List<Dependency>
    ) : AnalyzedBindingDependencies {
        data class Dependency(
            val node: AnalyzedNodeModel,
            val kind: DependencyKind,
        ) : GraphElement {
            override val id get() = null
            override val source get() = null
            override fun <R> accept(visitor: GraphElement.Visitor<R>): R {
                return visitor.visitRegularDependency(this)
            }
        }
    }

    class Alternatives(
        val alternatives: List<Alternative>
    ) : AnalyzedBindingDependencies {
        data class Alternative(
            val index: Int,
            val node: AnalyzedNodeModel,
            val condition: AnalyzedConditionScope,
        ) : GraphElement {
            override val id get() = null
            override val source get() = null
            override fun <R> accept(visitor: GraphElement.Visitor<R>): R {
                return visitor.visitAlternative(this)
            }
        }
    }

    class Multibinding(
        val kind: Kind,
        val contributions: List<Contribution>,
    ) : AnalyzedBindingDependencies {
        enum class Kind {
            Map,
            Set,
            List,
        }

        data class Contribution(
            val label: String,
            override val source: SmartPsiElementPointer<PsiElement>,
        ) : GraphElement {
            override val id get() = null
            override fun <R> accept(visitor: GraphElement.Visitor<R>): R {
                return visitor.visitMultibindingContribution(this)
            }
        }
    }
}