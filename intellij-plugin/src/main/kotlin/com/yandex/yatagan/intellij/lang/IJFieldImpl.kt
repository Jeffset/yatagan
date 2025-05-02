package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiField
import com.intellij.psi.PsiSubstitutor
import com.yandex.yatagan.lang.Annotated
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtFieldBase
import com.yandex.yatagan.lang.scope.FactoryKey
import com.yandex.yatagan.lang.scope.LexicalScope

internal class IJFieldImpl(
    override val owner: IJTypeDeclarationImpl,
    override val platformModel: PsiField,
    private val substitutor: PsiSubstitutor,
) : CtFieldBase(), CtAnnotated by IJAnnotated(owner, platformModel, PsiField::toUField, owner.tracker),
    LexicalScope by owner {

    override val isEffectivelyPublic: Boolean by lazy {
        platformModel.isPublic()
    }

    override val type: Type by lazy {
        IJTypeImpl(
            platformModel.type
                .substituteWith(substitutor)
                .substituteWith(owner.substitutor),
        ).also(owner.tracker::track)
    }

    override val isStatic: Boolean by lazy {
        platformModel.isStatic()
    }

    override val name: String
        get() = platformModel.name
}