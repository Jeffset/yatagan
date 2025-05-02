package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiSubstitutor
import com.yandex.yatagan.base.memoize
import com.yandex.yatagan.lang.Parameter
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtAnnotationBase
import com.yandex.yatagan.lang.compiled.CtMethodBase
import com.yandex.yatagan.lang.compiled.CtParameterBase
import com.yandex.yatagan.lang.scope.LexicalScope

internal class IJFunctionImpl(
    override val owner: IJTypeDeclarationImpl,
    override val platformModel: PsiMethod,
    private val substitutor: PsiSubstitutor,
) : CtMethodBase(), CtAnnotated by IJAnnotated(owner, platformModel, PsiMethod::toUMethod, owner.tracker),
    LexicalScope by owner {

    override val isEffectivelyPublic: Boolean by lazy {
        platformModel.isPublic()
    }

    override val parameters: Sequence<Parameter> by lazy {
        (0 until  platformModel.parameterList.parametersCount)
            .asSequence().map { ParameterImpl(it) }.memoize()
    }

    override val isAbstract: Boolean by lazy {
        platformModel.isAbstract()
    }

    override val returnType: Type by lazy {
        IJTypeImpl(
            platformModel.returnType!!
                .substituteWith(substitutor)
                .substituteWith(owner.substitutor),
        ).also(owner.tracker::track)
    }

    override val isStatic: Boolean by lazy { platformModel.isStatic() }

    override val name: String
        get() = platformModel.name

    private inner class ParameterImpl(
        private val index: Int,
    ): CtParameterBase(), LexicalScope by owner {
        private val annotated by lazy {
            IJAnnotated(owner, platformModel, PsiParameter::toUParameter, owner.tracker)
        }

        override val annotations: Sequence<CtAnnotationBase>
            get() = annotated.annotations

        override fun <A : Annotation> isAnnotatedWith(type: Class<A>) = annotated.isAnnotatedWith(type)

        override val name: String
            get() = platformModel.name

        override val type: Type by lazy {
            IJTypeImpl(
                type = platformModel.type
                    .substituteWith(substitutor)
                    .substituteWith(owner.substitutor),
            ).also(owner.tracker::track)
        }

        override val platformModel: PsiParameter by lazy {
            this@IJFunctionImpl.platformModel.parameterList.getParameter(index)!!
        }
    }
}