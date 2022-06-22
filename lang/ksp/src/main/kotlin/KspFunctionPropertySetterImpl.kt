package com.yandex.daggerlite.ksp.lang

import com.google.devtools.ksp.symbol.KSPropertySetter
import com.google.devtools.ksp.symbol.Modifier
import com.yandex.daggerlite.core.lang.ParameterLangModel
import com.yandex.daggerlite.core.lang.TypeLangModel

internal class KspFunctionPropertySetterImpl(
    private val setter: KSPropertySetter,
    override val owner: KspTypeDeclarationImpl,
    isStatic: Boolean,
) : KspFunctionPropertyAccessorBase<KSPropertySetter>(setter, isStatic) {

    override val isEffectivelyPublic: Boolean
        get() = super.isEffectivelyPublic && setter.modifiers.let {
            Modifier.PRIVATE !in it && Modifier.PROTECTED !in it
        }

    override val returnType: TypeLangModel = KspTypeImpl(Utils.resolver.builtIns.unitType)

    @Suppress("DEPRECATION")  // capitalize
    override val name: String by lazy {
        Utils.resolver.getJvmName(setter) ?: "set${property.simpleName.asString().capitalize()}"
    }

    override val parameters: Sequence<ParameterLangModel> = sequence {
        yield(KspParameterImpl(
            impl = setter.parameter,
            refinedTypeRef = property.type.replaceType(property.asMemberOf(owner.type.impl)),
            jvmSignatureSupplier = { jvmSignature },
        ))
    }

    override val platformModel: Any?
        get() = null
}