package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiWildcardType
import com.yandex.yatagan.base.castOrNull
import com.yandex.yatagan.intellij.lang.extra.localResolveScope
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.common.NoDeclaration
import com.yandex.yatagan.lang.compiled.CtTypeBase
import com.yandex.yatagan.lang.compiled.CtTypeNameModel
import com.yandex.yatagan.lang.scope.FactoryKey
import com.yandex.yatagan.lang.scope.LexicalScope
import com.yandex.yatagan.lang.scope.caching

internal class IJTypeImpl private constructor(
    lexicalScope: LexicalScope,
    private val resolved: ResolvedPsiType,
) : CtTypeBase(), LexicalScope by lexicalScope, HasDependencies {

    val psiType: PsiType
        get() = resolved.psiType

    override val nameModel: CtTypeNameModel by lazy {
        resolved.name()
    }

    override val dependencies: List<Dependency> by lazy {
        // Only depend on referenced PsiClass
        resolved.gatherTypeDependencies()
    }

    override val isUnresolved: Boolean
        get() = resolved is ResolvedPsiType.UnresolvedClass

    override val declaration: TypeDeclaration
        // No dependency on declaration - no lazy!
        get() = when (resolved) {
            is ResolvedPsiType.Class -> IJTypeDeclarationImpl(resolved)
            else -> NoDeclaration(this)
        }

    override val typeArguments: List<Type> by lazy {
        when(resolved) {
            is ResolvedPsiType.Class -> resolved.psiType.parameters.map {
                val mapped = it.accept(object : PsiTypeVisitor<PsiType>() {
                    override fun visitType(type: PsiType) = type
                    override fun visitWildcardType(wildcardType: PsiWildcardType): PsiType {
                        // Wildcard type decays into its bound or java.lang.Object for unbounded.
                        return wildcardType.bound
                            ?: PsiType.getJavaLangObject(Utils.psiManager, resolved.clazz.resolveScope)
                    }
                })
                Companion(ResolvedPsiType(mapped))
            }
            else -> emptyList()
        }
    }

    override val isVoid: Boolean
        get() = resolved == ResolvedPsiType.Primitive.Void

    fun isArray(): Boolean = resolved is ResolvedPsiType.ArrayType

    override fun isAssignableFrom(another: Type): Boolean {
        if (another !is IJTypeImpl) return false
        return psiType.isAssignableFrom(another.psiType)
    }

    override fun asBoxed(): IJTypeImpl {
        // Do not cache this as `localResolveScope` may have different JDKs (?)
        return resolved.castOrNull<ResolvedPsiType.Primitive>()?.psiType
            ?.getBoxedType(Utils.psiManager, localResolveScope)
            ?.let { Companion(ResolvedPsiType(it)) } ?: this
    }

    companion object : FactoryKey<ResolvedPsiType, IJTypeImpl> {
        override fun LexicalScope.factory() = caching { resolved: ResolvedPsiType -> IJTypeImpl(this, resolved) }
    }
}

internal fun LexicalScope.IJTypeImpl(type: PsiType): IJTypeImpl {
    return IJTypeImpl(ResolvedPsiType(type))
}

internal fun Type.isArray() = this is IJTypeImpl && isArray()

private fun ResolvedPsiType.gatherTypeDependencies(): List<Dependency> {
    return when(this) {
        is ResolvedPsiType.ArrayType -> elementType.gatherTypeDependencies()
        is ResolvedPsiType.Class -> buildList(1) {
            when(val file = clazz.containingFile) {
                null -> add(Dependency.World)
                else -> {
                    add(Dependency.Psi(file))
                    for (argument in arguments) addAll(argument.gatherTypeDependencies())
                }
            }
        }
        is ResolvedPsiType.TypeVariable -> when(val file = owner?.containingFile) {
            null -> listOf(Dependency.World)
            else -> listOf(Dependency.Psi(file))
        }
        is ResolvedPsiType.UnresolvedClass -> listOf(Dependency.World)
        is ResolvedPsiType.Wildcard -> listOfNotNull(
            upper?.gatherTypeDependencies(),
            lower?.gatherTypeDependencies(),
        ).flatten()
        is ResolvedPsiType.Primitive -> emptyList()
    }
}