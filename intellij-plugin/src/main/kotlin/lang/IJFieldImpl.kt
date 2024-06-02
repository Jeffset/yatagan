package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiField
import com.intellij.psi.PsiSubstitutor
import com.yandex.yatagan.lang.Annotated
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.compiled.CtFieldBase

internal class IJFieldImpl(
    override val owner: IJTypeDeclarationImpl,
    override val platformModel: PsiField,
    private val substitutor: PsiSubstitutor,
) : CtFieldBase(), Annotated by IJAnnotated(platformModel) {

    override val isEffectivelyPublic: Boolean
        get() = platformModel.isPublic()

    override val type: Type
        get() = IJTypeImpl(
            type = platformModel.type
                .substituteWith(substitutor)
                .substituteWith(owner.substitutor),
            project = platformModel.project,
        )

    override val isStatic: Boolean
        get() = platformModel.isStatic()

    override val name: String
        get() = platformModel.name
}