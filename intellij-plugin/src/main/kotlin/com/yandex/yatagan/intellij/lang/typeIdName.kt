package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiClass
import com.yandex.yatagan.lang.compiled.ArrayNameModel
import com.yandex.yatagan.lang.compiled.ClassNameModel
import com.yandex.yatagan.lang.compiled.CtTypeNameModel
import com.yandex.yatagan.lang.compiled.InvalidNameModel
import com.yandex.yatagan.lang.compiled.KeywordTypeNameModel
import com.yandex.yatagan.lang.compiled.ParameterizedNameModel
import com.yandex.yatagan.lang.compiled.WildcardNameModel

internal fun ResolvedPsiType.name(): CtTypeNameModel = when (this) {
    is ResolvedPsiType.ArrayType -> ArrayNameModel(
        elementType = elementType.name(),
    )
    is ResolvedPsiType.Class -> {
        val simpleNames = arrayListOf<String>()
        var packageName = ""
        var currentClass: PsiClass? = clazz
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
        if (arguments.isNotEmpty()) {
            ParameterizedNameModel(
                raw = ClassNameModel(packageName, simpleNames),
                typeArguments = arguments.map { it.name() },
            )
        } else {
            ClassNameModel(packageName, simpleNames)
        }
    }
    ResolvedPsiType.Primitive.Byte -> KeywordTypeNameModel.Byte
    ResolvedPsiType.Primitive.Char -> KeywordTypeNameModel.Char
    ResolvedPsiType.Primitive.Double -> KeywordTypeNameModel.Double
    ResolvedPsiType.Primitive.Float -> KeywordTypeNameModel.Float
    ResolvedPsiType.Primitive.Int -> KeywordTypeNameModel.Int
    ResolvedPsiType.Primitive.Long -> KeywordTypeNameModel.Long
    ResolvedPsiType.Primitive.Short -> KeywordTypeNameModel.Short
    ResolvedPsiType.Primitive.Boolean -> KeywordTypeNameModel.Boolean
    ResolvedPsiType.Primitive.Void -> KeywordTypeNameModel.Void
    is ResolvedPsiType.TypeVariable -> InvalidNameModel.TypeVariable(name)
    is ResolvedPsiType.Wildcard -> WildcardNameModel(
        upperBound = upper?.name(),
        lowerBound = lower?.name(),
    )

    is ResolvedPsiType.UnresolvedClass -> InvalidNameModel.Unresolved(psiType.className)
}
