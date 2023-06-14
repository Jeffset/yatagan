package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteral
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.search.GlobalSearchScope
import com.yandex.yatagan.base.ObjectCache
import com.yandex.yatagan.base.memoize
import com.yandex.yatagan.lang.Annotated
import com.yandex.yatagan.lang.Annotation
import com.yandex.yatagan.lang.AnnotationDeclaration
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.compiled.CtAnnotationDeclarationBase
import org.jetbrains.kotlin.asJava.elements.KtLightElement
import org.jetbrains.kotlin.idea.base.utils.fqname.fqName
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtElementImplStub
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.stubs.elements.KtStubElementTypes
import org.jetbrains.kotlin.resolve.annotations.argumentValue
import org.jetbrains.kotlin.resolve.constants.ArrayValue

internal class IJAnnotationImpl(
    override val platformModel: PsiAnnotation,
) : IJAnnotationBase() {
    private val valueCache = hashMapOf<String, Annotation.Value>()

    override val annotationClass: AnnotationDeclaration by plainLazy {
        AnnotationClassImpl.classOf(platformModel, platformModel.project)
    }

    override fun getValue(attribute: AnnotationDeclaration.Attribute): Annotation.Value {
        require(attribute is AttributeImpl) { "Invalid attribute type" }

        return valueCache.getOrPut(attribute.name, fun(): Annotation.Value {
            when(val platformModel = platformModel) {
//                is KtLightAnnotationForSourceEntry -> {
////                    platformModel
//                    TODO()
//                }
                else -> {
                    platformModel.findDeclaredAttributeValue(attribute.name)?.let {
                        return ValueImpl(it)
                    }
                    attribute.defaultValue?.let {
                        return ValueImpl(it)
                    }
                    return UnresolvedValue()
                }
            }
        })
    }

    internal class LazyImplForKt(
        override val platformModel: KtAnnotationEntry,
        private val context: PsiElement,
    ) : IJAnnotationBase() {
        private val valueCache = hashMapOf<String, Annotation.Value>()

        override val annotationClass: AnnotationDeclaration by lazy {
            AnnotationClassImpl.classOf(platformModel, context)
        }

        override fun getValue(attribute: AnnotationDeclaration.Attribute): Annotation.Value {
            require(attribute is AttributeImpl) { "Invalid attribute type" }
            return valueCache.getOrPut(attribute.name) {
                getDeclaredValue(attribute) ?: attribute.defaultValue?.let { ValueImpl(it) } ?: UnresolvedValue()
            }
        }

        private fun findValueArgument(attribute: AttributeImpl): List<KtValueArgument> {
            val argList = platformModel.getStubOrPsiChild(KtStubElementTypes.VALUE_ARGUMENT_LIST) ?: return emptyList()
            val varargs = arrayListOf<KtValueArgument>()
            argList.arguments.forEachIndexed { index, argument ->
                val name = argument.getArgumentName()?.asName?.asString()
                if (name != null) {
                    if (varargs.isNotEmpty()) {
                        // Vararg end
                        return varargs
                    }
                    if (name == attribute.name) {
                        return listOf(argument)
                    }
                } else {
                    val varargIndex = (annotationClass as? AnnotationClassImpl)?.varargAttributeIndex
//                    println("Index for $annotationClass = $varargIndex")
                    if (varargIndex != null && varargIndex <= index) {
                        // This is a vararg argument contribution
                        if (varargIndex == attribute.index) {
                            varargs.add(argument)
                        }
                    } else {
                        if (index == attribute.index) {
                            return listOf(argument)
                        }
                    }
                }
            }
            return varargs
        }

        private fun getDeclaredValue(attribute: AttributeImpl): Annotation.Value? {
            val expectedType = (attribute.type as? IJTypeImpl)?.type ?: return null

            val argumentValues = findValueArgument(attribute)
//            println("Values count for ${attribute.name} = ${argumentValues.size}")

            if (argumentValues.isEmpty()) {
                return null
            }

            val values = argumentValues.map {
                val expression = it.getArgumentExpression() ?: return null
                Utils.evaluateExpression(expression, expectedType, context) ?: return null
            }

            return ValueImpl(
                value = values.singleOrNull() ?: ArrayValue(values) { throw AssertionError() },
                project = context.project,
                resolveScope = context.resolveScope,
            )
        }
    }

    private class ImplForKt(
        override val platformModel: KotlinAnnotationDescriptor,
        private val project: Project,
        private val resolveScope: GlobalSearchScope,
    ) : IJAnnotationBase() {
        private val valueCache = hashMapOf<String, Annotation.Value>()

        override val annotationClass: AnnotationDeclaration by plainLazy {
            AnnotationClassImpl.classOf(platformModel, project, resolveScope)
        }

        override fun getValue(attribute: AnnotationDeclaration.Attribute): Annotation.Value {
            require(attribute is AttributeImpl) { "Invalid attribute type" }
            return valueCache.getOrPut(attribute.name, fun(): Annotation.Value {
                platformModel.argumentValue(attribute.name)?.let { constantValue ->
                    return ValueImpl(constantValue, project, resolveScope)
                }
                attribute.defaultValue?.let {
                    return ValueImpl(it)
                }
                return UnresolvedValue()
            })
        }
    }

    private class ValueImpl private constructor(
        override val platformModel: Any?,
        private val project: Project,
        private val resolveScope: GlobalSearchScope,
    ) : ValueBase() {
        constructor(value: PsiAnnotationMemberValue): this(value, value.project, value.resolveScope)
        constructor(
            value: KotlinConstantValue,
            project: Project,
            resolveScope: GlobalSearchScope,
        ): this(value as Any?, project, resolveScope)

        override fun <R> accept(visitor: Annotation.Value.Visitor<R>): R {
            fun visitConstantOrKotlinModel(value: Any?): R = when(value) {
                // True constants (from java or kotlin values)
                is Boolean -> visitor.visitBoolean(value)
                is Byte -> visitor.visitByte(value)
                is Short -> visitor.visitShort(value)
                is Int -> visitor.visitInt(value)
                is Long -> visitor.visitLong(value)
                is Char -> visitor.visitChar(value)
                is Float -> visitor.visitFloat(value)
                is Double -> visitor.visitDouble(value)
                is String -> visitor.visitString(value)
                // Kotlin models:
                is KotlinConstantValue -> visitConstantOrKotlinModel(value.value)
                is KotlinAnnotationDescriptor -> visitor.visitAnnotation(ImplForKt(value, project, resolveScope))
                is KotlinNormalClassValue -> {  // Value from `KClassValue`
                    with(JavaPsiFacade.getInstance(project)) {
                        val clazz = value.classId.findPsiClassKotlinAware(project, resolveScope)
                        if (clazz != null) {
                            visitor.visitType(IJTypeImpl(elementFactory.createType(clazz), project))
                        } else {
                            visitor.visitUnresolved()
                        }
                    }
                }
                is List<*> -> {  // Value from `ArrayValue`
                    // Type-enforce the list contents
                    visitor.visitArray(value.map { ValueImpl(it as KotlinConstantValue, project, resolveScope) })
                }
                is Pair<*, *> -> {  // Value from `EnumValue`
                    val classId = value.first as KotlinClassId
                    val name  = value.second as KotlinName
                    with(JavaPsiFacade.getInstance(project)) {
                        val enumClass = classId.findPsiClassKotlinAware(project, resolveScope)
                        if (enumClass != null) {
                            visitor.visitEnumConstant(
                                enum = IJTypeImpl(elementFactory.createType(enumClass), project),
                                constant = name.asString(),
                            )
                        } else {
                            visitor.visitUnresolved()
                        }
                    }
                }

                null -> visitor.visitUnresolved()
                else -> throw IllegalStateException("Unexpected value: ${debugString(value)}")
            }

            fun evaluateAndVisitConstantExpression(expression: PsiExpression): R {
                val constant = JavaPsiFacade.getInstance(project).constantEvaluationHelper
                    .computeConstantExpression(expression)
                return visitConstantOrKotlinModel(constant)
            }

            return when(val element = platformModel) {
                is PsiAnnotationMemberValue -> when(element) {
                    is PsiLiteral -> visitConstantOrKotlinModel(element.value)
                    is PsiAnnotation -> visitor.visitAnnotation(IJAnnotationImpl(element))
                    is PsiExpression -> {
                        when (element) {
                            is PsiClassObjectAccessExpression -> visitor.visitType(
                                IJTypeImpl(element.operand.type, project))

                            is PsiReferenceExpression -> when (val resolved = element.resolve()) {
                                is PsiEnumConstant -> visitor.visitEnumConstant(
                                    enum = IJTypeImpl(resolved.type, project),
                                    constant = resolved.name,
                                )
                                is PsiExpression -> evaluateAndVisitConstantExpression(resolved)
                                is PsiField -> evaluateAndVisitConstantExpression(element)
                                else -> throw AssertionError(
                                    "Unexpected reference resolution result: ${debugString(resolved)}")
                            }

                            else -> evaluateAndVisitConstantExpression(element)
                        }
                    }
                    is PsiArrayInitializerMemberValue -> {
                        visitor.visitArray(element.initializers.map { ValueImpl(it, project, resolveScope) })
                    }
                    else -> throw AssertionError("Unexpected PsiAnnotationMemberValue: ${debugString(element)}")
                }
                is KotlinConstantValue -> visitConstantOrKotlinModel(element.value)
                null -> visitor.visitUnresolved()
                else -> throw AssertionError("Not reached: ${debugString(element)}")
            }
        }

        override fun hashCode(): Int {
            return identity.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            return this === other || (other is ValueImpl && identity == other.identity)
        }

        private val identity by plainLazy {
            accept(object : Annotation.Value.Visitor<Any?> {
                override fun visitBoolean(value: Boolean) = value
                override fun visitByte(value: Byte) = value
                override fun visitShort(value: Short) = value
                override fun visitInt(value: Int) = value
                override fun visitLong(value: Long) = value
                override fun visitChar(value: Char) = value
                override fun visitFloat(value: Float) = value
                override fun visitDouble(value: Double) = value
                override fun visitString(value: String) = value
                override fun visitType(value: Type) = value
                override fun visitAnnotation(value: Annotation) = value
                override fun visitEnumConstant(enum: Type, constant: String) = enum to constant
                override fun visitArray(value: List<Annotation.Value>) = value
                override fun visitUnresolved() = null
            })
        }
    }

    private class AnnotationClassImpl private constructor(
        val platformModel: PsiClass,
        override val qualifiedName: String,
        private val project: Project,
    ) : CtAnnotationDeclarationBase(), Annotated by IJAnnotated(platformModel) {

        val varargAttributeIndex by lazy {
            if (platformModel is KtLightElement<*, *>) {
                val ktClass = platformModel.kotlinOrigin as? KtElementImplStub<*>
                ktClass?.getStubOrPsiChild(KtStubElementTypes.PRIMARY_CONSTRUCTOR)
                    ?.valueParameters?.indexOfFirst { it.isVarArg }
                    .takeIf { it != -1 }
            } else null
        }

        override val attributes by lazy {
            platformModel.methods.asSequence()
                .filter { it.isAbstract() }
                .filterIsInstance<PsiAnnotationMethod>()
                .mapIndexed { index, it -> AttributeImpl(it, project, index) }
                .memoize()
        }

        override fun getRetention(): AnnotationRetention {
            val retention = platformModel.annotations.find {
                it.hasQualifiedName("java.lang.annotation.Retention")
            }
            val reference = retention?.findAttributeValue("value") as? PsiReferenceExpression
            val value = reference?.resolve() as? PsiEnumConstant
            return when(value?.name) {
                "SOURCE" -> AnnotationRetention.SOURCE
                "CLASS" -> AnnotationRetention.BINARY
                "RUNTIME" -> AnnotationRetention.RUNTIME
                else -> AnnotationRetention.BINARY
            }
        }

        companion object Factory : ObjectCache<String, AnnotationDeclaration>() {
            fun classOf(
                annotation: PsiAnnotation,
                project: Project,
            ): AnnotationDeclaration {
                val qualifiedName = annotation.qualifiedName ?: return UnresolvedAnnotationClass("<unresolved>")
                return createCached(qualifiedName) {
                    when(val clazz = annotation.resolveAnnotationTypeKotlinAware()) {
                        null -> {
                            val facade = JavaPsiFacade.getInstance(project)
                            val textuallyResolvedClass = facade.findClass(qualifiedName, annotation.resolveScope)
                            if (textuallyResolvedClass != null) {
                                AnnotationClassImpl(
                                    qualifiedName = qualifiedName,
                                    platformModel = textuallyResolvedClass,
                                    project = project,
                                )
                            } else UnresolvedAnnotationClass(qualifiedName)
                        }
                        else -> AnnotationClassImpl(
                            qualifiedName = qualifiedName,
                            platformModel = clazz,
                            project = project,
                        )
                    }
                }
            }

            fun classOf(
                annotation: KotlinAnnotationDescriptor,
                project: Project,
                resolveScope: GlobalSearchScope,
            ): AnnotationDeclaration {
                val qualifiedName = annotation.fqName ?: return UnresolvedAnnotationClass("<unresolved>")
                val qualifiedNameString = qualifiedName.asString()
                return createCached(qualifiedNameString) {
                    when(val clazz = qualifiedName.findPsiClassKotlinAware(project, resolveScope)) {
                        null -> {
                            val facade = JavaPsiFacade.getInstance(project)
                            val textuallyResolvedClass = facade.findClass(qualifiedNameString, resolveScope)
                            if (textuallyResolvedClass != null) {
                                AnnotationClassImpl(
                                    platformModel = textuallyResolvedClass,
                                    qualifiedName = qualifiedNameString,
                                    project = project,
                                )
                            } else UnresolvedAnnotationClass(qualifiedNameString)
                        }
                        else -> AnnotationClassImpl(
                            platformModel = clazz,
                            qualifiedName = qualifiedNameString,
                            project = project,
                        )
                    }
                }
            }

            fun classOf(
                annotation: KtAnnotationEntry,
                context: PsiElement,
            ): AnnotationDeclaration {
                Utils.lightResolveAnnotationFqName(annotation, context.resolveScope)?.let { lightResolveClass ->
                    val qualifiedName = lightResolveClass.qualifiedName!!
                    return createCached(qualifiedName) {
                        AnnotationClassImpl(
                            platformModel = lightResolveClass,
                            qualifiedName = qualifiedName,
                            project = context.project,
                        )
                    }.also {
//                        println("Resolved annotation class $qualifiedName lightly")
                    }
                }

                val qualifiedName = Utils.resolveKotlinType(annotation.typeReference!!, context)?.fqName
                    ?: return UnresolvedAnnotationClass("+" + (annotation.text ?: "<unresolved>"))
                val qualifiedNameString = qualifiedName.asString()
                return createCached(qualifiedNameString) {
                    val clazz = qualifiedName.findPsiClassKotlinAware(context.project, context.resolveScope)
                        ?: JavaPsiFacade.getInstance(context.project).findClass(qualifiedNameString, context.resolveScope)
                        ?: return@createCached UnresolvedAnnotationClass(qualifiedNameString)

//                    println("Resolved kt annotation class ${clazz.qualifiedName}")
                    AnnotationClassImpl(
                        platformModel = clazz,
                        qualifiedName = qualifiedNameString,
                        project = context.project,
                    )
                }
            }
        }
    }

    private class AttributeImpl(
        val platformModel: PsiAnnotationMethod,
        private val project: Project,
        val index: Int,
    ) : AnnotationDeclaration.Attribute {
        val defaultValue: PsiAnnotationMemberValue? by plainLazy { platformModel.defaultValue }

        override val name: String
            get() = platformModel.name

        override val type: Type by plainLazy {
            IJTypeImpl.ofNullable(platformModel.returnType, project)
        }
    }
}