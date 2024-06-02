package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiSubstitutor
import com.yandex.yatagan.base.ObjectCache
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
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.asJava.toLightGetter
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.psiUtil.isPublic
import org.jetbrains.uast.UParameter
import org.jetbrains.uast.toUElementOfType

internal class IJTypeDeclarationImpl private constructor(
    override val platformModel: PsiClass,
    private val project: Project,
    val substitutor: PsiSubstitutor,
) : CtTypeDeclarationBase(), CtAnnotated by IJAnnotated(platformModel) {

    override val kind: TypeDeclarationKind by lazy {
        platformModel.let {
            when {
                it.isAnnotationType -> TypeDeclarationKind.Annotation
                it.isInterface -> TypeDeclarationKind.Interface
                it.isEnum -> TypeDeclarationKind.Enum
                else -> when (val maybeObject = (platformModel as? KtLightClass)?.kotlinOrigin) {
                    is KtObjectDeclaration -> if (maybeObject.isCompanion()) {
                        TypeDeclarationKind.KotlinCompanion
                    } else {
                        TypeDeclarationKind.KotlinObject
                    }

                    else -> TypeDeclarationKind.Class
                }
            }
        }
    }

    override val isAbstract: Boolean by lazy {
        platformModel.let { it.isAbstract() && !it.isAnnotationType }
    }

    override val qualifiedName: String by plainLazy {
        platformModel.qualifiedName ?: ""
    }

    override val enclosingType: TypeDeclaration? by lazy {
        platformModel.containingClass?.let { parent ->
            Factory(parent)
        }
    }

    override val interfaces: Sequence<Type>
        get() = TODO("Not yet implemented")

    override val superType: Type?
        get() = TODO("Not yet implemented")

    override val constructors: Sequence<Constructor> by plainLazy {
        val platformModel = platformModel

        if (platformModel is KtLightElement<*, *>) {
            val kotlinOrigin = platformModel.kotlinOrigin as? KtClassOrObject
            if (kotlinOrigin != null) {
                return@plainLazy sequence {
                    val primary = kotlinOrigin.primaryConstructor
                }
            }
        }

        val constructors = platformModel.constructors
            .asSequence()

//        val constructors = allMethodsAndTheirSubstitutors
//            .asSequence().filter { (it, _) -> it.isConstructor && it.containingClass == platformModel }
        if (constructors.none()) {
            // Default java constructor
            sequenceOf(DefaultConstructorImpl())
        } else {
            constructors.filter {
                !it.isPrivate()
            }.map { method ->
                ConstructorImpl(
                    platformModel = method,
                    substitutor = substitutor,
                )
            }.memoize()
        }
    }

    override val methods: Sequence<Method> by plainLazy {
        val isCompanion = kind == TypeDeclarationKind.KotlinCompanion
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

    override val fields: Sequence<Field> by plainLazy {
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
            Factory(it, substitutor /* TODO: is this substitutor ok here? */)
        }.memoize()
    }

    override val defaultCompanionObjectDeclaration: TypeDeclaration? by lazy {
        when(val model = platformModel) {
            is KtLightClass -> model.kotlinOrigin?.companionObjects?.find {
                it.name == "Companion"
            }?.toLightClass()?.let { Factory(it) }

            else -> null
        }
    }

    override fun asType(): Type {
        return cachedType
    }

//    override fun equals(other: Any?): Boolean {
//        if (this === other) return true
//        if (javaClass != other?.javaClass) return false
//
//        other as IJTypeDeclarationImpl
//
//        if (substitutor != other.substitutor) return false
//        if (qualifiedName != other.qualifiedName) return false
//
//        return true
//    }
//
//    override fun hashCode(): Int {
//        var result = substitutor.hashCode()
//        result = 31 * result + qualifiedName.hashCode()
//        return result
//    }

    override val isEffectivelyPublic: Boolean
        get() = platformModel.isPublic()

    private val cachedType by plainLazy {
        val factory = PsiElementFactory.getInstance(project)
        IJTypeImpl(factory.createType(platformModel, substitutor), project)
    }

    private inner class DefaultConstructorImpl : CtConstructorBase() {
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

    private inner class ConstructorKtImpl(
        override val platformModel: KtConstructor<*>,
    ) : CtConstructorBase() {
        override val isEffectivelyPublic: Boolean
            get() = platformModel.isPublic  // TODO: Fixme

        override val annotations: Sequence<Annotation> by lazy {
            platformModel.annotationEntries.asSequence().map {
                IJAnnotationImpl.LazyImplForKt(it, platformModel)
            }.memoize()
        }

        override val parameters: Sequence<Parameter> by lazy {
            platformModel.valueParameters.asSequence()
                .map { ParameterImpl(it) }
                .memoize()
        }

        override val constructee: TypeDeclaration
            get() = this@IJTypeDeclarationImpl

        private inner class ParameterImpl(
            override val platformModel: KtParameter,
        ) : CtParameterBase() {
            override val name: String
                get() = platformModel.name ?: "<???>"

            override val type: Type by lazy {
                platformModel.toLightGetter()
                val typeReference = platformModel.typeReference
                val kotlinType = Utils.resolveKotlinType(typeReference!!, platformModel)
                val type = platformModel.toUElementOfType<UParameter>()!!.type
                IJTypeImpl(type, project)
            }

            override val annotations: Sequence<CtAnnotationBase>
                get() = TODO("Not yet implemented")
        }
    }

    private inner class ConstructorImpl(
        override val platformModel: PsiMethod,
        private val substitutor: PsiSubstitutor,
    ) : CtConstructorBase(), Annotated by IJAnnotated(platformModel) {
        override val isEffectivelyPublic: Boolean
            get() = platformModel.isPublic()

        override val parameters: Sequence<Parameter> by lazy {
            (0 until platformModel.parameterList.parametersCount).asSequence()
                .map { ParameterImpl(it) }
                .memoize()
        }

        override val constructee: TypeDeclaration
            get() = this@IJTypeDeclarationImpl

        private inner class ParameterImpl(
            private val index: Int,
        ) : CtParameterBase() {
            private val annotated by lazy {
                IJAnnotated(platformModel)
            }

            override val annotations: Sequence<CtAnnotationBase>
                get() = annotated.annotations

            override val name: String
                get() = platformModel.name

            override val type: Type by lazy {
                IJTypeImpl(
                    type = platformModel.type
                        .substituteWith(substitutor)
                        .substituteWith(this@IJTypeDeclarationImpl.substitutor),
                    project = project,
                )
            }

            override val platformModel: PsiParameter by lazy {
                this@ConstructorImpl.platformModel.parameterList.getParameter(index)!!
            }
        }
    }

    companion object Factory : ObjectCache<Pair<String, PsiSubstitutor>, IJTypeDeclarationImpl>() {
        operator fun invoke(
            psiClass: PsiClass,
            substitutor: PsiSubstitutor = PsiSubstitutor.EMPTY,
        ): IJTypeDeclarationImpl {
            val name = psiClass.qualifiedName ?: psiClass.hashCode().toString()
            val key = name to substitutor
            return createCached(key) {
                IJTypeDeclarationImpl(
                    platformModel = psiClass,
                    substitutor = substitutor,
                    project = psiClass.project,
                )
            }
        }
    }
}
