package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameterListOwner
import com.intellij.psi.PsiTypeVariable
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.yandex.yatagan.base.ifOrElseNull
import com.yandex.yatagan.intellij.lang.ResolvedPsiType.Primitive

@Suppress("StatefulEp")  // "Hard Psi References" - It's okay because we manage dependencies manually
internal sealed interface ResolvedPsiType {
    val psiType: PsiType

    class Class(
        val qualifiedName: String,
        val arguments: List<ResolvedPsiType>,  // TODO: Maybe make this lazy somehow?
        val locationId: Any,
        // Extra:
        val clazz: PsiClass,
        val substitutor: PsiSubstitutor,
        override val psiType: PsiClassType,
    ) : ResolvedPsiType {

        private val hash by plainLazy {
            var result = qualifiedName.hashCode()
            result = 31 * result + arguments.hashCode()
            result = 31 * result + locationId.hashCode()
            result
        }

        override fun equals(other: Any?) = this === other || other is Class
                && qualifiedName == other.qualifiedName
                && arguments == other.arguments
                && locationId == other.locationId

        override fun hashCode() = hash
        override fun toString(): String = "Class [$qualifiedName<$arguments>]"
    }

    class Wildcard(
        val upper: ResolvedPsiType?,
        val lower: ResolvedPsiType?,
        // Extra:
        override val psiType: PsiWildcardType,
    ) : ResolvedPsiType {
        private val hash by plainLazy { 31 * upper.hashCode() + lower.hashCode() }

        override fun equals(other: Any?) = this === other || other is Wildcard
                && upper == other.upper && lower == other.lower

        override fun hashCode() = hash

        override fun toString(): String {
            return "Wildcard [$lower < . < $upper]"
        }
    }

    class TypeVariable(
        // Extra:
        val name: String,
        val owner: PsiTypeParameterListOwner?,
        override val psiType: PsiTypeVariable,
    ) : ResolvedPsiType {
        override fun toString(): String {
            return "TypeVariable [$name, owner = ${owner?.name}]"
        }
    }

    class ArrayType(
        val elementType: ResolvedPsiType,
        // Extra:
        override val psiType: PsiArrayType,
    ) : ResolvedPsiType {
        override fun equals(other: Any?) = this === other || other is ArrayType
                && elementType == other.elementType

        override fun hashCode() = elementType.hashCode() * 2

        override fun toString() = "Array [$elementType]"
    }

    class UnresolvedClass(
        // Extra:
        override val psiType: PsiClassType,
    ) : ResolvedPsiType {
        override fun toString() = "Unresolved [$psiType]"
    }

    enum class Primitive(override val psiType: PsiPrimitiveType) : ResolvedPsiType {
        Byte(PsiTypes.byteType()),
        Char(PsiTypes.charType()),
        Double(PsiTypes.doubleType()),
        Float(PsiTypes.floatType()),
        Int(PsiTypes.intType()),
        Long(PsiTypes.longType()),
        Short(PsiTypes.shortType()),
        Boolean(PsiTypes.booleanType()),
        Void(PsiTypes.voidType()),
    }

    companion object {
        operator fun invoke(type: PsiType): ResolvedPsiType = type.accept(PsiTypeResolver)
    }
}

private object PsiTypeResolver : PsiTypeVisitor<ResolvedPsiType>() {
    override fun visitClassType(classType: PsiClassType): ResolvedPsiType {
        val (clazz: PsiClass?, substitutor: PsiSubstitutor?) = run {
            val result = classType.resolveGenerics()
            result.element to result.substitutor.takeIf { it !== PsiSubstitutor.EMPTY }
        }
        val qualifiedName = clazz?.qualifiedName
            ?: return ResolvedPsiType.UnresolvedClass(classType)

        return ResolvedPsiType.Class(
            qualifiedName = qualifiedName,
            locationId = clazz.containingFile?.virtualFile?.path ?: System.identityHashCode(clazz),
            arguments = substitutor?.let { classType.parameters.map { it.accept(this) } } ?: emptyList(),
            clazz = clazz,
            substitutor = substitutor ?: PsiSubstitutor.EMPTY,
            psiType = classType,
        )
    }

    override fun visitPrimitiveType(primitiveType: PsiPrimitiveType): Primitive = when (primitiveType) {
        Primitive.Byte.psiType -> Primitive.Byte
        Primitive.Char.psiType -> Primitive.Char
        Primitive.Double.psiType -> Primitive.Double
        Primitive.Float.psiType -> Primitive.Float
        Primitive.Int.psiType -> Primitive.Int
        Primitive.Long.psiType -> Primitive.Long
        Primitive.Short.psiType -> Primitive.Short
        Primitive.Boolean.psiType -> Primitive.Boolean
        Primitive.Void.psiType -> Primitive.Void
        else -> throw IllegalStateException("Unexpected primitive type: `$primitiveType`")
    }

    override fun visitWildcardType(wildcardType: PsiWildcardType): ResolvedPsiType {
        return ResolvedPsiType.Wildcard(
            upper = ifOrElseNull(wildcardType.isExtends) { wildcardType.extendsBound.accept(this) },
            lower = ifOrElseNull(wildcardType.isSuper) { wildcardType.superBound.accept(this) },
            psiType = wildcardType,
        )
    }

    override fun visitArrayType(arrayType: PsiArrayType): ResolvedPsiType {
        return ResolvedPsiType.ArrayType(
            elementType = arrayType.componentType.accept(this),
            psiType = arrayType,
        )
    }

    override fun visitType(type: PsiType): Nothing = throw IllegalStateException("Unexpected PsiType: $type")
}