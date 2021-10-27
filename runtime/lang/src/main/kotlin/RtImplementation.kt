package com.yandex.daggerlite.rt.lang

import com.yandex.daggerlite.Component
import com.yandex.daggerlite.Module
import com.yandex.daggerlite.core.lang.AnnotatedLangModel
import com.yandex.daggerlite.core.lang.AnnotationLangModel
import com.yandex.daggerlite.core.lang.ComponentAnnotationLangModel
import com.yandex.daggerlite.core.lang.FunctionLangModel
import com.yandex.daggerlite.core.lang.ModuleAnnotationLangModel
import com.yandex.daggerlite.core.lang.ParameterLangModel
import com.yandex.daggerlite.core.lang.TypeDeclarationLangModel
import com.yandex.daggerlite.core.lang.TypeLangModel
import com.yandex.daggerlite.core.lang.memoize
import java.lang.reflect.AnnotatedElement
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import javax.inject.Qualifier
import javax.inject.Scope
import kotlin.LazyThreadSafetyMode.NONE
import kotlin.reflect.KClass

// TODO: split this into separate source files per class.

internal class RtAnnotationImpl(
    private val impl: Annotation,
) : AnnotationLangModel {
    override val isScope: Boolean
        get() = impl.javaAnnotationClass.isAnnotationPresent(Scope::class.java)
    override val isQualifier: Boolean
        get() = impl.javaAnnotationClass.isAnnotationPresent(Qualifier::class.java)

    override fun <A : Annotation> hasType(type: Class<A>): Boolean {
        return impl.javaAnnotationClass == type
    }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is RtAnnotationImpl && other.impl == impl)
    }

    override fun hashCode() = impl.hashCode()
}

class RtComponentAnnotationImpl(
    impl: Component,
) : ComponentAnnotationLangModel {
    override val isRoot: Boolean = impl.isRoot
    override val modules: Sequence<TypeLangModel> = impl.modules.asSequence()
        .map(KClass<*>::java).map(::RtTypeDeclarationImpl).memoize()
    override val dependencies: Sequence<TypeLangModel> = impl.modules.asSequence()
        .map(KClass<*>::java).map(::RtTypeDeclarationImpl).memoize()
}

class RtModuleAnnotationImpl(
    impl: Module,
) : ModuleAnnotationLangModel {
    override val includes: Sequence<TypeLangModel> = impl.includes.asSequence()
        .map(KClass<*>::java).map(::RtTypeDeclarationImpl).memoize()
    override val subcomponents: Sequence<TypeLangModel> = impl.subcomponents.asSequence()
        .map(KClass<*>::java).map(::RtTypeDeclarationImpl).memoize()
}

abstract class RtAnnotatedImpl : AnnotatedLangModel {
    protected abstract val impl: AnnotatedElement

    override val annotations: Sequence<AnnotationLangModel> by lazy(NONE) {
        impl.annotations.asSequence().map(::RtAnnotationImpl).memoize()
    }

    override fun <A : Annotation> isAnnotatedWith(type: Class<A>): Boolean {
        return impl.isAnnotationPresent(type)
    }

    override fun <A : Annotation> getAnnotation(type: Class<A>): AnnotationLangModel {
        return RtAnnotationImpl(impl.getAnnotation(type)!!)
    }
}

class RtTypeDeclarationImpl(
    override val impl: Class<*>,
) : RtAnnotatedImpl(), TypeDeclarationLangModel, TypeLangModel {
    override val isAbstract: Boolean
        get() = Modifier.isAbstract(impl.modifiers)
    override val isKotlinObject: Boolean by lazy(NONE) {
        impl.isAnnotationPresent(Metadata::class.java) && impl.declaredFields.any {
            // TODO: improve this heuristic.
            it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers)
        }
    }
    override val qualifiedName: String
        get() = impl.canonicalName
    override val constructors: Sequence<FunctionLangModel> = impl.constructors.asSequence().map {
        RtConstructorImpl(owner = this, impl = it)
    }.memoize()
    override val allPublicFunctions: Sequence<FunctionLangModel> = impl.declaredMethods.asSequence().map {
        RtFunctionImpl(owner = this, impl = it)
    }
    override val nestedInterfaces: Sequence<TypeDeclarationLangModel> = impl.declaredClasses
        .asSequence().filter(Class<*>::isInterface).map(::RtTypeDeclarationImpl)

    override fun asType(): TypeLangModel {
        require(impl.typeParameters.isEmpty())
        return this
    }

    override val declaration: TypeDeclarationLangModel get() = this
    override val typeArguments: Sequence<Nothing> get() = emptySequence()

    override val componentAnnotationIfPresent: ComponentAnnotationLangModel?
        get() = impl.getAnnotation(Component::class.java)?.let(::RtComponentAnnotationImpl)
    override val moduleAnnotationIfPresent: ModuleAnnotationLangModel?
        get() = impl.getAnnotation(Module::class.java)?.let(::RtModuleAnnotationImpl)

    override fun equals(other: Any?): Boolean {
        return this === other || (other is RtTypeDeclarationImpl && impl == other.impl)
    }

    override fun hashCode() = impl.hashCode()
}

class RtTypeImpl(
    private val impl: Type,
) : TypeLangModel {
    override val declaration: TypeDeclarationLangModel by lazy(NONE) {
        RtTypeDeclarationImpl(
            when (impl) {
                is Class<*> -> impl
                is ParameterizedType -> impl.rawType as Class<*>
                else -> throw UnsupportedOperationException("no type declaration implemented for ${impl.javaClass}")
            }
        )
    }
    override val typeArguments: Sequence<TypeLangModel> = when (impl) {
        is ParameterizedType -> impl.actualTypeArguments.asSequence().map(::RtTypeImpl).memoize()
        else -> emptySequence()
    }

    override fun equals(other: Any?): Boolean {
        return this === other || (other is RtTypeImpl && impl == other.impl)
    }

    override fun hashCode(): Int = impl.hashCode()
}

class RtParameterImpl(
    override val annotations: Sequence<AnnotationLangModel>,
    override val name: String,
    override val type: TypeLangModel,
) : ParameterLangModel

class RtConstructorImpl(
    override val owner: TypeDeclarationLangModel,
    override val impl: Constructor<*>,
    override val isFromCompanionObject: Boolean = TODO("implement it")
) : RtAnnotatedImpl(), FunctionLangModel {
    override val isConstructor: Boolean get() = true
    override val isAbstract: Boolean get() = false
    override val isStatic: Boolean get() = true

    override val returnType: TypeLangModel get() = owner.asType()
    override val name: String get() = impl.name
    override val parameters: Sequence<ParameterLangModel> = parametersOf(impl)
}

class RtFunctionImpl(
    override val owner: TypeDeclarationLangModel,
    override val impl: Executable,
    override val isFromCompanionObject: Boolean = TODO("implement it")
) : RtAnnotatedImpl(), FunctionLangModel {
    override val isConstructor: Boolean get() = false
    override val isAbstract: Boolean get() = Modifier.isAbstract(impl.modifiers)
    override val isStatic: Boolean get() = Modifier.isStatic(impl.modifiers)
    override val returnType: TypeLangModel by lazy(NONE) {
        RtTypeImpl(impl.annotatedReturnType.type)
    }
    override val name: String get() = impl.name
    override val parameters: Sequence<ParameterLangModel> = parametersOf(impl)
}

fun parametersOf(impl: Executable): Sequence<ParameterLangModel> = impl
    .parameters.asSequence()
    .zip(impl.parameterAnnotations.asSequence())
    .map { (param, annotations) ->
        RtParameterImpl(
            annotations = annotations.asSequence().map(::RtAnnotationImpl).memoize(),
            name = param.name,
            type = RtTypeImpl(param.parameterizedType),
        )
    }.memoize()