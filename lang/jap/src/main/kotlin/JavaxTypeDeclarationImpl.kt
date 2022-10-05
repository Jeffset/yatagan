package com.yandex.daggerlite.jap.lang

import com.yandex.daggerlite.base.ObjectCache
import com.yandex.daggerlite.base.mapToArray
import com.yandex.daggerlite.base.memoize
import com.yandex.daggerlite.core.lang.AnnotatedLangModel
import com.yandex.daggerlite.core.lang.ConstructorLangModel
import com.yandex.daggerlite.core.lang.FieldLangModel
import com.yandex.daggerlite.core.lang.FunctionLangModel
import com.yandex.daggerlite.core.lang.KotlinObjectKind
import com.yandex.daggerlite.core.lang.ParameterLangModel
import com.yandex.daggerlite.core.lang.TypeDeclarationLangModel
import com.yandex.daggerlite.core.lang.TypeLangModel
import com.yandex.daggerlite.generator.lang.CtAnnotatedLangModel
import com.yandex.daggerlite.generator.lang.CtTypeDeclarationLangModel
import com.yandex.daggerlite.lang.common.ConstructorLangModelBase
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.NestingKind
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.type.ArrayType
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.type.TypeVariable
import javax.lang.model.type.WildcardType
import javax.lang.model.util.TypeKindVisitor7
import kotlin.LazyThreadSafetyMode.PUBLICATION

internal class JavaxTypeDeclarationImpl private constructor(
    val type: DeclaredType,
) : CtAnnotatedLangModel by JavaxAnnotatedImpl(type.asTypeElement()), CtTypeDeclarationLangModel() {
    private val impl = type.asTypeElement()

    override val isEffectivelyPublic: Boolean
        get() = impl.isPublic

    override val isInterface: Boolean
        get() = impl.kind == ElementKind.INTERFACE

    override val isAbstract: Boolean
        get() = impl.isAbstract

    override val kotlinObjectKind: KotlinObjectKind?
    get() = when {
        impl.isDefaultCompanionObject() -> KotlinObjectKind.Companion
        impl.isKotlinSingleton() -> KotlinObjectKind.Object
        else -> null
    }

    override val qualifiedName: String
        get() = impl.qualifiedName.toString()

    override val enclosingType: TypeDeclarationLangModel?
        get() = when (impl.nestingKind) {
            NestingKind.MEMBER -> Factory(impl.enclosingElement.asType().asDeclaredType())
            else -> null
        }

    override val interfaces: Sequence<TypeLangModel> by lazy {
        impl.interfaces.asSequence()
            .map { JavaxTypeImpl(it.asMemberOfThis()) }
            .memoize()
    }

    override val superType: TypeLangModel? by lazy {
        impl.superclass.takeIf {
            it.kind == TypeKind.DECLARED && it.asTypeElement() != Utils.objectType
        }?.let { superClass ->
            JavaxTypeImpl(superClass.asMemberOfThis())
        }
    }

    override val constructors: Sequence<ConstructorLangModel> by lazy {
        impl.enclosedElements
        .asSequence()
        .filter { it.kind == ElementKind.CONSTRUCTOR && !it.isPrivate }
        .map {
            ConstructorImpl(platformModel = it.asExecutableElement())
        }.memoize()
    }

    override val functions: Sequence<FunctionLangModel> by lazy {
        impl.allNonPrivateMethods()
        .run {
            when (kotlinObjectKind) {
                KotlinObjectKind.Companion -> filterNot {
                    // Such methods already have a truly static counterpart so skip them.
                    it.isAnnotatedWith<JvmStatic>()
                }
                else -> this
            }
        }
        .map {
            JavaxFunctionImpl(
                owner = this@JavaxTypeDeclarationImpl,
                impl = it,
            )
        }.memoize()
    }

    override val fields: Sequence<FieldLangModel> by lazy {
        impl.allNonPrivateFields()
        .map { JavaxFieldImpl(owner = this, impl = it) }
        .memoize()
    }

    override val nestedClasses: Sequence<TypeDeclarationLangModel> by lazy {
        impl.enclosedElements
        .asSequence()
        .filter {
            when (it.kind) {
                ElementKind.ENUM, ElementKind.CLASS,
                ElementKind.ANNOTATION_TYPE, ElementKind.INTERFACE,
                -> !it.isPrivate
                else -> false
            }
        }
        .map { Factory(it.asType().asDeclaredType()) }
        .memoize()
    }

    override val defaultCompanionObjectDeclaration: TypeDeclarationLangModel? by lazy {
        if (impl.isFromKotlin()) {
            impl.enclosedElements.find {
                it.kind == ElementKind.CLASS && it.asTypeElement().isDefaultCompanionObject()
            }?.let { JavaxTypeDeclarationImpl(it.asType().asDeclaredType()) }
        } else null
    }

    override fun asType(): TypeLangModel {
        return JavaxTypeImpl(type)
    }

    override val platformModel: TypeElement get() = impl

    private val genericsInfo: Map<TypeParameterElement, TypeMirror> by lazy(PUBLICATION) {
        impl.typeParameters
            .zip(type.typeArguments)
            .toMap()
    }

    private fun TypeMirror.asMemberOfThis(): TypeMirror {
        return accept(object : TypeKindVisitor7<TypeMirror, Nothing?>(this) {
            override fun visitTypeVariable(type: TypeVariable, p: Nothing?): TypeMirror {
                // Actual resolution happens here
                return genericsInfo[type.asElement().asTypeParameterElement()] ?: type
            }

            override fun visitDeclared(type: DeclaredType, p: Nothing?): TypeMirror {
                if (type.typeArguments.isEmpty())
                    return type

                val resolved = type.typeArguments.mapToArray { it.asMemberOfThis() }
                return Utils.types.getDeclaredType(type.asTypeElement(), *resolved)
            }

            override fun visitWildcard(type: WildcardType, p: Nothing?): TypeMirror {
                return Utils.types.getWildcardType(
                    type.extendsBound?.asMemberOfThis(),
                    type.superBound?.asMemberOfThis(),
                )
            }

            override fun visitArray(type: ArrayType, p: Nothing?): TypeMirror {
                return Utils.types.getArrayType(
                    type.componentType.asMemberOfThis(),
                )
            }
        }, null)
    }

    companion object Factory : ObjectCache<TypeMirrorEquivalence, JavaxTypeDeclarationImpl>() {
        operator fun invoke(
            impl: DeclaredType,
        ): JavaxTypeDeclarationImpl {
            return createCached(TypeMirrorEquivalence(impl)) {
                JavaxTypeDeclarationImpl(type = impl)
            }
        }
    }

    private inner class ConstructorImpl(
        override val platformModel: ExecutableElement,
    ) : ConstructorLangModelBase(), AnnotatedLangModel by JavaxAnnotatedImpl(platformModel) {
        override val isEffectivelyPublic: Boolean get() = platformModel.isPublic
        override val constructee: TypeDeclarationLangModel get() = this@JavaxTypeDeclarationImpl
        override val parameters: Sequence<ParameterLangModel> = parametersSequenceFor(platformModel, type)
    }
}