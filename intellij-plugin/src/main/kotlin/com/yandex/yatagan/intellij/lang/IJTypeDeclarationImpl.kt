package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiSubstitutor
import com.yandex.yatagan.base.memoize
import com.yandex.yatagan.lang.Annotated
import com.yandex.yatagan.lang.Annotation
import com.yandex.yatagan.lang.Constructor
import com.yandex.yatagan.lang.Field
import com.yandex.yatagan.lang.Method
import com.yandex.yatagan.lang.Parameter
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.TypeDeclarationKind
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtAnnotationBase
import com.yandex.yatagan.lang.compiled.CtConstructorBase
import com.yandex.yatagan.lang.compiled.CtParameterBase
import com.yandex.yatagan.lang.compiled.CtTypeDeclarationBase
import com.yandex.yatagan.lang.scope.FactoryKey
import com.yandex.yatagan.lang.scope.LexicalScope
import com.yandex.yatagan.lang.scope.caching

internal class IJTypeDeclarationImpl private constructor(
    lexicalScope: LexicalScope,
    private val resolved: ResolvedPsiType.Class,
    val tracker: DependenciesTracker = DependenciesTracker()
) : CtTypeDeclarationBase(),
    CtAnnotated by IJAnnotated(lexicalScope, resolved.clazz, PsiClass::toUClass, tracker),
    LexicalScope by lexicalScope,
    HasDependencies by tracker {
    override val platformModel: PsiClass = resolved.clazz
    internal val substitutor: PsiSubstitutor =  resolved.substitutor

    private val _isInterface by lazy { platformModel.isInterface }
    private val _isKotlinSingleton by lazy(fun(): Boolean {
        if (!platformModel.isFromKotlin())
            return false
        val instanceField = platformModel.findFieldByName("INSTANCE", false) ?: return false
        return instanceField.hasModifierProperty(PsiModifier.STATIC) &&
                instanceField.hasModifierProperty(PsiModifier.FINAL) &&
                ResolvedPsiType(instanceField.type) == resolved
    })

    init {
        tracker.track(platformModel)
    }

    override val kind: TypeDeclarationKind by lazy {
        when {
            platformModel.isAnnotationType -> TypeDeclarationKind.Annotation
            isInterface() -> TypeDeclarationKind.Interface
            platformModel.isEnum -> TypeDeclarationKind.Enum
            isKotlinSingleton() -> TypeDeclarationKind.KotlinObject
            isCompanion() -> TypeDeclarationKind.KotlinCompanion
            else -> TypeDeclarationKind.Class
        }
    }

    override fun isInterface(): Boolean = _isInterface

    override fun isKotlinSingleton(): Boolean = _isKotlinSingleton

    override fun isKotlinSingletonOrCompanion(): Boolean {
        return isKotlinSingleton() || isCompanion()
    }

    private fun isCompanion(): Boolean {
        return enclosingType?.defaultCompanionObjectDeclaration == this
    }

    override val isAbstract: Boolean by lazy {
        platformModel.let { it.isAbstract() && !it.isAnnotationType }
    }

    override val qualifiedName: String by plainLazy {
        platformModel.qualifiedName ?: ""
    }

    override val enclosingType: TypeDeclaration? by lazy {
        platformModel.containingClass?.let { parent ->
            IJTypeImpl(Utils.factory.createType(parent)).declaration
        }
    }

    override val interfaces: Sequence<Type>
        get() = TODO("Not yet implemented")

    override val superType: Type?
        get() = TODO("Not yet implemented")

    override val constructors: Sequence<Constructor> by lazy {
        val constructors = platformModel.constructors
        if (constructors.isEmpty()) {
            // Default java constructor
            sequenceOf(DefaultConstructorImpl())
        } else {
            constructors.asSequence().filter {
                !it.isPrivate()
            }.map { method ->
                ConstructorImpl(
                    platformModel = method,
                    substitutor = substitutor,
                )
            }.memoize()
        }
    }

    override val methods: Sequence<Method> by lazy {
        trackSupersStamp

        val isCompanion = isCompanion()

        platformModel.allMethodsAndTheirSubstitutors
            .groupBy { (method, _) -> method.name }
            .flatMap { (_, methods) ->
                if (methods.size > 1) {
                    methods.distinctBy { (method, _) -> method.getSignature(PsiSubstitutor.EMPTY) }
                } else methods
            }.asSequence()
            .filter { (it, _) ->
                !it.isConstructor && !it.isPrivate() && it.containingClass?.qualifiedName != "java.lang.Object" &&
                        // Such methods already have a truly static counterpart so skip them.
                        (!isCompanion || !it.hasAnnotation("kotlin.jvm.JvmStatic"))
            }
            .map { (method, substitutor) ->
                IJFunctionImpl(
                    owner = this@IJTypeDeclarationImpl,
                    platformModel = method,
                    substitutor = substitutor,
                )
            }.memoize()
    }

    override val fields: Sequence<Field> by lazy {
        trackSupersStamp

        platformModel.allFieldsWithTheirSubstitutors.asSequence()
            .filter { (it, _) ->
                !it.isPrivate() && it.containingClass?.qualifiedName != "java.lang.Object"
            }.map { (field, substitutor) ->
                IJFieldImpl(
                    owner = this@IJTypeDeclarationImpl,
                    platformModel = field,
                    substitutor = substitutor,
                )
            }.memoize()
    }

    override val nestedClasses: Sequence<TypeDeclaration> by lazy {
        platformModel.innerClasses.asSequence().map {
            // TODO: Need to provide correct type args/substitutor for non-static inner classes
            IJTypeImpl(Utils.factory.createType(it)).also(tracker::track).declaration
        }.memoize()
    }

    override val defaultCompanionObjectDeclaration: TypeDeclaration? by lazy(fun(): TypeDeclaration? {
        if (!platformModel.isFromKotlin()) {
            return null
        }

        val maybeCompanionField = platformModel.findFieldByName("Companion", false) ?: return null
        if (!maybeCompanionField.hasModifierProperty(PsiModifier.STATIC) ||
            !maybeCompanionField.hasModifierProperty(PsiModifier.FINAL)
        ) {
            return null
        }

        return platformModel.findInnerClassByName("Companion", false)?.let { companion ->
            IJTypeImpl(Utils.factory.createType(companion)).declaration
        }
    })

    override fun asType(): Type {
        return IJTypeImpl(resolved)
    }

    override val isEffectivelyPublic: Boolean by lazy { platformModel.isPublic() }

    private inner class DefaultConstructorImpl : CtConstructorBase(), LexicalScope by this {
        override val platformModel: Nothing? get() = null

        override val isEffectivelyPublic: Boolean
            get() = this@IJTypeDeclarationImpl.isEffectivelyPublic

        override val annotations: Sequence<Annotation>
            get() = emptySequence()

        override val parameters: Sequence<Parameter>
            get() = emptySequence()

        override val constructee: TypeDeclaration
            get() = this@IJTypeDeclarationImpl
    }

    private fun trackSupers(clazz: PsiClass) {
        clazz.superTypes.forEach {
            val resolved = ResolvedPsiType(it)
            val superType = IJTypeImpl(resolved)
            tracker.track(superType)
            when (resolved) {
                is ResolvedPsiType.Class -> trackSupers(resolved.clazz)
                else -> Unit
            }
        }
    }

    private val trackSupersStamp: Unit by lazy {
        trackSupers(platformModel)
    }

    private inner class ConstructorImpl (
        override val platformModel: PsiMethod,
        private val substitutor: PsiSubstitutor,
    ) : CtConstructorBase(), Annotated by IJAnnotated(this, platformModel, PsiMethod::toUMethod, tracker),
        LexicalScope by this {

        override val isEffectivelyPublic: Boolean by lazy { platformModel.isPublic() }

        override val parameters: Sequence<Parameter> by lazy {
            (0 until platformModel.parameterList.parametersCount).asSequence()
                .map { ParameterImpl(it) }
                .memoize()
        }

        override val constructee: TypeDeclaration
            get() = this@IJTypeDeclarationImpl

        private inner class ParameterImpl(
            private val index: Int,
        ) : CtParameterBase(), LexicalScope by this {
            private val annotated by lazy {
                IJAnnotated(this@IJTypeDeclarationImpl, platformModel, PsiParameter::toUParameter, tracker)
            }

            override val annotations: Sequence<CtAnnotationBase>
                get() = annotated.annotations

            override val name: String
                get() = platformModel.name

            override val type: Type by lazy {
                IJTypeImpl(
                    type = platformModel.type
                        .substituteWith(substitutor)
                ).also(tracker::track)
            }

            override val platformModel: PsiParameter by lazy {
                this@ConstructorImpl.platformModel.parameterList.getParameter(index)!!
            }
        }
    }

    companion object : FactoryKey<ResolvedPsiType.Class, IJTypeDeclarationImpl> {
        override fun LexicalScope.factory() = caching(::IJTypeDeclarationImpl)
    }
}
