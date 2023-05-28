package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiSubstitutor
import com.yandex.yatagan.lang.Parameter
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtAnnotationBase
import com.yandex.yatagan.lang.compiled.CtMethodBase
import com.yandex.yatagan.lang.compiled.CtParameterBase

internal class IJFunctionImpl(
    override val owner: IJTypeDeclarationImpl,
    override val platformModel: PsiMethod,
    private val substitutor: PsiSubstitutor,
) : CtMethodBase(), CtAnnotated by IJAnnotated(platformModel) {

    override val isEffectivelyPublic: Boolean
        get() = platformModel.isPublic()

    override val parameters: Sequence<Parameter>
        get() = (0 until  platformModel.parameterList.parametersCount)
            .asSequence().map { ParameterImpl(it) }

    override val isAbstract: Boolean
        get() = platformModel.isAbstract()

    override val returnType: Type
        get() = IJTypeImpl(
            type = platformModel.returnType!!
                .substituteWith(substitutor)
                .substituteWith(owner.substitutor),
            project = platformModel.project,
        )

    override val isStatic: Boolean
        get() = platformModel.isStatic()

    override val name: String
        get() = platformModel.name

    private inner class ParameterImpl(
        private val index: Int,
    ): CtParameterBase() {
        override val annotations: Sequence<CtAnnotationBase>
            get() = platformModel.parameterList.getParameter(index)!!.annotations
                .asSequence().map { IJAnnotationImpl(it) }

        override val name: String
            get() = platformModel.parameterList.getParameter(index)!!.name

        override val type: Type
            get() = IJTypeImpl(
                type = platformModel.parameterList.getParameter(index)!!.type
                    .substituteWith(substitutor)
                    .substituteWith(owner.substitutor),
                project = platformModel.project,
            )
    }
}