package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeVisitor
import com.intellij.psi.impl.PsiClassImplUtil
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.elements.KtLightField
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.elements.KtLightParameter
import org.jetbrains.kotlin.asJava.elements.isGetter
import org.jetbrains.kotlin.asJava.elements.isSetter
import org.jetbrains.kotlin.builtins.jvm.JavaToKotlinClassMap
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.psi.KtAnnotated
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElementOfExpectedTypes

internal typealias KotlinNormalClassValue = org.jetbrains.kotlin.resolve.constants.KClassValue.Value.NormalClass
internal typealias KotlinConstantValue = org.jetbrains.kotlin.resolve.constants.ConstantValue<*>
internal typealias KotlinName = org.jetbrains.kotlin.name.Name
internal typealias KotlinClassId = org.jetbrains.kotlin.name.ClassId
internal typealias KotlinAnnotationDescriptor = org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
internal typealias KotlinFqName = org.jetbrains.kotlin.name.FqName

internal typealias IJPair<T1, T2> = com.intellij.openapi.util.Pair<T1, T2>

internal fun PsiType.asClassType(): PsiClassType? {
    return accept(object : PsiTypeVisitor<PsiClassType?>() {
        override fun visitType(type: PsiType) = null
        override fun visitClassType(classType: PsiClassType) = classType
    })
}

internal fun PsiType.asPrimitiveType(): PsiPrimitiveType? {
    return accept(object : PsiTypeVisitor<PsiPrimitiveType?>() {
        override fun visitType(type: PsiType) = null
        override fun visitPrimitiveType(primitiveType: PsiPrimitiveType) = primitiveType
    })
}

internal fun PsiAnnotation.resolveAnnotationTypeKotlinAware(): PsiClass? {
    // Kotlin UAST annotation references doesn't resolve to PsiClass.
    // Instead, they resolve to Kotlin's annotation class constructor, whose UAST is UMethod.
    val declaration = nameReferenceElement?.resolve()?.toUElementOfExpectedTypes(
        UClass::class.java,
        UMethod::class.java,
    )?.javaPsi ?: return null
    val clazz = PsiTreeUtil.getParentOfType(declaration, PsiClass::class.java, false) ?: return null
    return clazz.takeIf { it.isAnnotationType }
}

internal fun PsiModifierListOwner.isPrivate(): Boolean {
    return hasModifierProperty(PsiModifier.PRIVATE)
}

internal fun PsiModifierListOwner.isPublic(): Boolean {
    return hasModifierProperty(PsiModifier.PUBLIC)
}

internal fun PsiModifierListOwner.isAbstract(): Boolean {
    return hasModifierProperty(PsiModifier.ABSTRACT)
}

internal fun PsiModifierListOwner.isStatic(): Boolean {
    return hasModifierProperty(PsiModifier.STATIC)
}

internal val PsiClass.allFieldsWithTheirSubstitutors: List<IJPair<PsiField, PsiSubstitutor>>
    get() = PsiClassImplUtil.getAllWithSubstitutorsByMap(this, PsiClassImplUtil.MemberType.FIELD)

internal fun PsiType.substituteWith(substitutor: PsiSubstitutor): PsiType {
    return substitutor.substitute(this)!!
}

internal fun KotlinFqName.findPsiClassKotlinAware(
    project: Project,
    resolveScope: GlobalSearchScope,
): PsiClass? {
    return toUnsafe().findPsiClassKotlinAware(project, resolveScope)
}

internal fun KotlinClassId.findPsiClassKotlinAware(
    project: Project,
    resolveScope: GlobalSearchScope,
): PsiClass? {
    return FqNameUnsafe(asFqNameString()).findPsiClassKotlinAware(project, resolveScope)
}

internal fun FqNameUnsafe.findPsiClassKotlinAware(
    project: Project,
    resolveScope: GlobalSearchScope,
): PsiClass? {
    return with(JavaPsiFacade.getInstance(project)) {
        val name = JavaToKotlinClassMap.mapKotlinToJava(this@findPsiClassKotlinAware)?.asFqNameString() ?: asString()
        findClass(name, resolveScope)
    }
}

internal fun debugString(value: Any?) = if (value != null) {
    "`$value` of `${value.javaClass}`"
} else "`null`"

@Suppress("NOTHING_TO_INLINE")
internal inline operator fun <T> IJPair<T, *>.component1(): T = first

@Suppress("NOTHING_TO_INLINE")
internal inline operator fun <T> IJPair<*, T>.component2(): T = second

internal inline fun <T> plainLazy(crossinline initializer: () -> T) = lazy(LazyThreadSafetyMode.NONE) { initializer() }

enum class KotlinToJavaRelation {
    Direct,
    Field,
//    File,
    Property,
    PropertyGetter,
    PropertySetter,
    Receiver,
    ConstructorParameter,
    SetterParameter,
//    PropertyDelegateField,
}

fun PsiElement.detectKotlinOriginWithUseSiteTarget(): Pair<KtAnnotated, KotlinToJavaRelation>? {
    if (this !is KtLightElement<*, *>) {
        return null
    }

    val kotlinImpl: KtAnnotated = this.kotlinOrigin as? KtAnnotated ?: return null

    val target = when(kotlinImpl) {
        is KtProperty, is KtParameter -> when(this) {
            is KtLightField -> KotlinToJavaRelation.Field
            is KtLightParameter -> {
                val method = this.method
                when {
                    method.isSetter -> KotlinToJavaRelation.SetterParameter
                    method.isConstructor -> KotlinToJavaRelation.ConstructorParameter
                    else -> KotlinToJavaRelation.Direct
                }
            }
            is KtLightMethod -> when {
                this.isSetter -> KotlinToJavaRelation.PropertySetter
                this.isGetter -> KotlinToJavaRelation.PropertyGetter
                else -> KotlinToJavaRelation.Property
            }
            else -> throw AssertionError("not reached")
        }
        else -> KotlinToJavaRelation.Direct
    }
    return kotlinImpl to target
}