package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import com.yandex.yatagan.base.ObjectCache
import com.yandex.yatagan.base.ifOrElseNull
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.common.NoDeclaration
import com.yandex.yatagan.lang.compiled.ArrayNameModel
import com.yandex.yatagan.lang.compiled.ClassNameModel
import com.yandex.yatagan.lang.compiled.CtErrorType
import com.yandex.yatagan.lang.compiled.CtTypeBase
import com.yandex.yatagan.lang.compiled.CtTypeNameModel
import com.yandex.yatagan.lang.compiled.InvalidNameModel
import com.yandex.yatagan.lang.compiled.KeywordTypeNameModel
import com.yandex.yatagan.lang.compiled.ParameterizedNameModel
import com.yandex.yatagan.lang.compiled.WildcardNameModel

internal class IJTypeImpl private constructor(
    val type: PsiType,
    val project: Project,
) : CtTypeBase() {
    override val nameModel: CtTypeNameModel by plainLazy {
        type.accept(object : PsiTypeVisitor<CtTypeNameModel>() {
            override fun visitClassType(classType: PsiClassType): CtTypeNameModel {
                val simpleNames = arrayListOf<String>()
                var currentClass: PsiClass? = classType.resolve()
                    ?: return InvalidNameModel.Unresolved(hint = classType.presentableText)
                if (currentClass is PsiTypeParameter) {
                    return InvalidNameModel.TypeVariable(classType.presentableText)
                }
                var packageName = ""
                while (currentClass != null) {
                    simpleNames += currentClass.name ?: "<error>"
                    val containingClass = currentClass.containingClass
                    if (containingClass == null) {
                        packageName = currentClass.qualifiedName?.substringBeforeLast(
                            delimiter = '.',
                            missingDelimiterValue = "",
                        ) ?: ""
                    }
                    currentClass = containingClass
                }
                simpleNames.reverse()
                val className = ClassNameModel(packageName, simpleNames)
                return if (classType.parameterCount > 0) {
                    ParameterizedNameModel(
                        raw = className,
                        typeArguments = classType.parameters.map { it.accept(this) },
                    )
                } else className
            }

            override fun visitPrimitiveType(primitiveType: PsiPrimitiveType) = when (primitiveType) {
                PsiTypes.byteType() -> KeywordTypeNameModel.Byte
                PsiTypes.charType() -> KeywordTypeNameModel.Char
                PsiTypes.doubleType() -> KeywordTypeNameModel.Double
                PsiTypes.floatType() -> KeywordTypeNameModel.Float
                PsiTypes.intType() -> KeywordTypeNameModel.Int
                PsiTypes.longType() -> KeywordTypeNameModel.Long
                PsiTypes.shortType() -> KeywordTypeNameModel.Short
                PsiTypes.booleanType() -> KeywordTypeNameModel.Boolean
                PsiTypes.voidType() -> KeywordTypeNameModel.Void
                else -> throw IllegalStateException("Unexpected primitive type: `$primitiveType`")
            }

            override fun visitWildcardType(wildcardType: PsiWildcardType): CtTypeNameModel {
                return WildcardNameModel(
                    upperBound = ifOrElseNull(wildcardType.isExtends) { wildcardType.extendsBound.accept(this) },
                    lowerBound = ifOrElseNull(wildcardType.isSuper) { wildcardType.superBound.accept(this) },
                )
            }

            override fun visitArrayType(arrayType: PsiArrayType) = ArrayNameModel(arrayType.componentType.accept(this))

            override fun visitType(type: PsiType) = throw IllegalStateException("$type is not expected/supported here")
        })
    }

    override val declaration: TypeDeclaration by plainLazy {
        type.asClassType()?.let { type ->
            val result = type.resolveGenerics()
            val clazz = result.element
            if (clazz != null) {
                IJTypeDeclarationImpl(
                    psiClass = clazz,
                    substitutor = result.substitutor,
                )
            } else null
        } ?: NoDeclaration(this@IJTypeImpl)
    }

    override val typeArguments: List<Type>
        get() = type.asClassType()?.let { type ->
            type.parameters.map {
                Factory(it.accept(object : PsiTypeVisitor<PsiType>() {
                    override fun visitType(type: PsiType) = type
                    override fun visitWildcardType(wildcardType: PsiWildcardType): PsiType {
                        // Wildcard type decays into its bound or java.lang.Object for unbounded.
                        return wildcardType.bound ?: PsiType.getJavaLangObject(
                            PsiManager.getInstance(project),
                            GlobalSearchScope.allScope(project),
                        )
                    }
                }), project)
            }
        } ?: emptyList()

    override val isVoid: Boolean
        get() = type.asPrimitiveType() == PsiTypes.voidType()

    override fun isAssignableFrom(another: Type): Boolean {
        if (another !is IJTypeImpl) return false
        return type.isAssignableFrom(another.type)
    }

    override fun asBoxed(): Type {
        return type.asPrimitiveType()?.let { primitive ->
            BoxedTypeFactory(
                primitive = primitive,
                project = project,
            )
        } ?: this
    }

    private object BoxedTypeFactory : ObjectCache<PsiPrimitiveType, IJTypeImpl>() {
        operator fun invoke(
            primitive: PsiPrimitiveType,
            project: Project,
        ): Type {
            return createCached(primitive) {
                Factory(
                    type = primitive.getBoxedType(PsiManager.getInstance(project),
                        GlobalSearchScope.allScope(project))!!,
                    project = project,
                )
            }
        }
    }

    companion object Factory : ObjectCache<PsiType, IJTypeImpl>() {
        operator fun invoke(
            type: PsiType,
            project: Project,
        ): IJTypeImpl {
            return createCached(type) {
                IJTypeImpl(
                    type = it,
                    project = project,
                )
            }
        }

        operator fun invoke(
            type: PsiType?,
            project: Project,
        ): Type {
            type ?: return CtErrorType(
                nameModel = InvalidNameModel.Unresolved(hint = null),
            )
            return Factory(type, project)
        }
    }
}