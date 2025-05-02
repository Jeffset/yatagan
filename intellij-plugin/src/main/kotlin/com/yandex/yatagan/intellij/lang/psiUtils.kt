package com.yandex.yatagan.intellij.lang

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.impl.PsiClassImplUtil
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElementOfType

internal typealias IJPair<T1, T2> = com.intellij.openapi.util.Pair<T1, T2>

internal fun PsiElement.isFromKotlin(): Boolean {
    return language.id.contentEquals("kotlin", false)
}

internal fun PsiElement.isFromJava(): Boolean =
    language === JavaLanguage.INSTANCE

internal fun PsiClass.toUClass(): UClass { return toUElementOfType<UClass>()!! }
internal fun PsiField.toUField(): UField { return toUElementOfType<UField>()!! }
internal fun PsiMethod.toUMethod(): UMethod { return toUElementOfType<UMethod>()!! }
internal fun PsiParameter.toUParameter(): UParameter { return toUElementOfType<UParameter>()!! }

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

@Suppress("NOTHING_TO_INLINE")
internal inline operator fun <T> IJPair<T, *>.component1(): T = first

@Suppress("NOTHING_TO_INLINE")
internal inline operator fun <T> IJPair<*, T>.component2(): T = second

internal inline fun <T> plainLazy(crossinline initializer: () -> T) = lazy(LazyThreadSafetyMode.NONE) { initializer() }
