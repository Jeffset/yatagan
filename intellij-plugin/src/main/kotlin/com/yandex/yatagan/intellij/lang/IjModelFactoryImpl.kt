package com.yandex.yatagan.intellij.lang

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiWildcardType
import com.yandex.yatagan.intellij.lang.extra.localResolveScope
import com.yandex.yatagan.lang.LangModelFactory
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.compiled.CtLangModelFactoryBase
import com.yandex.yatagan.lang.scope.LexicalScope

internal class IjModelFactoryImpl(
    private val lexicalScope: LexicalScope,
) : CtLangModelFactoryBase(), LexicalScope by lexicalScope {
    private val utils = Utils
    private val facade = JavaPsiFacade.getInstance(utils.project)
    private val elementFactory = facade.elementFactory

    private val listClass: PsiClass get() = facade.findClass("java.util.List", localResolveScope)!!
    private val mapClass: PsiClass get() = facade.findClass("java.util.Map", localResolveScope)!!
    private val setClass: PsiClass get() = facade.findClass("java.util.Set", localResolveScope)!!
    private val collectionClass: PsiClass get() = facade.findClass("java.util.Collection", localResolveScope)!!
    private val providerClass: PsiClass get() = facade.findClass("javax.inject.Provider", localResolveScope)!!

    override fun getParameterizedType(
        type: LangModelFactory.ParameterizedType,
        parameter: Type,
        isCovariant: Boolean,
    ): Type {
        if (parameter !is IJTypeImpl) {
            return super.getParameterizedType(type, parameter, isCovariant)
        }
        val element = when(type) {
            LangModelFactory.ParameterizedType.List -> listClass
            LangModelFactory.ParameterizedType.Set -> setClass
            LangModelFactory.ParameterizedType.Collection -> collectionClass
            LangModelFactory.ParameterizedType.Provider -> providerClass
        }
        val param = if (isCovariant) {
            PsiWildcardType.createExtends(utils.psiManager, parameter.psiType)
        } else parameter.psiType
        return IJTypeImpl(elementFactory.createType(element, param))
    }

    override fun getMapType(keyType: Type, valueType: Type, isCovariant: Boolean): Type {
        if (keyType !is IJTypeImpl || valueType !is IJTypeImpl) {
            return super.getMapType(keyType, valueType, isCovariant)
        }
        val valueParam = if (isCovariant) {
            PsiWildcardType.createExtends(utils.psiManager, valueType.psiType)
        } else valueType.psiType
        return IJTypeImpl(elementFactory.createType(mapClass, keyType.psiType, valueParam))
    }

    override fun getTypeDeclaration(
        packageName: String,
        simpleName: String,
        vararg simpleNames: String,
    ): TypeDeclaration? {
        val name = buildString {
            if (packageName.isNotEmpty()) {
                append(packageName).append('.')
            }
            append(simpleName)
            for (name in simpleNames) append('.').append(name)
        }
        val declaration = facade.findClass(name, localResolveScope)
        return declaration?.let { IJTypeImpl(elementFactory.createType(declaration)).declaration }
    }

    override val isInRuntimeEnvironment: Boolean
        get() = false
}