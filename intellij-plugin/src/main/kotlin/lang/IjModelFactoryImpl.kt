package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.search.GlobalSearchScope
import com.yandex.yatagan.lang.LangModelFactory
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.compiled.CtLangModelFactoryBase

class IjModelFactoryImpl(
    private val project: Project, // FIXME: That's a botched idea to store a Project in a singleton
) : CtLangModelFactoryBase() {
    private val facade = JavaPsiFacade.getInstance(project)
    private val elementFactory = facade.elementFactory
    private val scope = GlobalSearchScope.allScope(project)

    private val listClass: PsiClass by plainLazy { facade.findClass("java.util.List", scope)!! }
    private val mapClass: PsiClass by plainLazy { facade.findClass("java.util.Map", scope)!! }
    private val setClass: PsiClass by plainLazy { facade.findClass("java.util.Set", scope)!! }
    private val collectionClass: PsiClass by plainLazy { facade.findClass("java.util.Collection", scope)!! }
    private val providerClass: PsiClass by plainLazy { facade.findClass("javax.inject.Provider", scope)!! }

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
            PsiWildcardType.createExtends(PsiManager.getInstance(project), parameter.type)
        } else parameter.type
        return IJTypeImpl(elementFactory.createType(element, param), project)
    }

    override fun getMapType(keyType: Type, valueType: Type, isCovariant: Boolean): Type {
        if (keyType !is IJTypeImpl || valueType !is IJTypeImpl) {
            return super.getMapType(keyType, valueType, isCovariant)
        }
        val valueParam = if (isCovariant) {
            PsiWildcardType.createExtends(PsiManager.getInstance(project), valueType.type)
        } else valueType.type
        return IJTypeImpl(elementFactory.createType(mapClass, keyType.type, valueParam), project)
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
        val declaration = facade.findClass(name, scope)
        return declaration?.let { IJTypeDeclarationImpl(it) }
    }

    override val isInRuntimeEnvironment: Boolean
        get() = false
}