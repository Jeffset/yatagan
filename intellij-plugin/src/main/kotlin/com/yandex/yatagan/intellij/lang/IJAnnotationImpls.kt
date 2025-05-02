package com.yandex.yatagan.intellij.lang

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiAnnotationMethod
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiLiteral
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.yandex.yatagan.base.api.Internal
import com.yandex.yatagan.base.memoize
import com.yandex.yatagan.base.wrapIf
import com.yandex.yatagan.lang.Annotation
import com.yandex.yatagan.lang.AnnotationDeclaration
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.common.AnnotationBase.ValueBase
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtAnnotationDeclarationBase
import com.yandex.yatagan.lang.compiled.CtErrorType
import com.yandex.yatagan.lang.compiled.InvalidNameModel
import com.yandex.yatagan.lang.scope.FactoryKey
import com.yandex.yatagan.lang.scope.LexicalScope
import com.yandex.yatagan.lang.scope.caching
import org.jetbrains.uast.UAnnotation
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClassLiteralExpression
import org.jetbrains.uast.UEnumConstant
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.toUElementOfType

private class IJAnnotationUastImpl(
    lexicalScope: LexicalScope,
    private val uAnnotation: UAnnotation,
    private val tracker: DependenciesTracker,
) : IJAnnotation(), LexicalScope by lexicalScope {
    // No need to track anything here, as the file is already tracked

    private val valueCache = hashMapOf<String, Annotation.Value>()

    override val platformModel: PsiElement?
        get() = uAnnotation.sourcePsi

    override val annotationClass: AnnotationDeclaration by plainLazy {
        AnnotationClassImpl.classOf(this, uAnnotation).also(tracker::track)
    }

    override fun getValue(attribute: AnnotationDeclaration.Attribute): Annotation.Value {
        require(attribute is AnnotationClassImpl.AttributeImpl) { "Invalid attribute type" }
        return valueCache.getOrPut(attribute.name) {
            uAnnotation.findDeclaredAttributeValue(attribute.name)?.let { value ->
                ValueImpl(
                    visit = makeVisitUExpression(value, tracker)
                        .wrapIf(attribute.type.isArray()) { wrapIntoArrayIfNeeded(it, tracker) },
                    tracker = tracker,
                )
            } ?: attribute.defaultValue ?: ValueImpl(VisitUnresolved, tracker)
        }
    }
}

internal fun LexicalScope.IJAnnotation(
    psiAnnotation: PsiAnnotation,
    tracker: DependenciesTracker,
): IJAnnotation {
    return IJAnnotationPsiImpl(this, psiAnnotation, tracker)
}

internal fun LexicalScope.IJAnnotation(
    uAnnotation: UAnnotation,
    tracker: DependenciesTracker,
): IJAnnotation {
    return IJAnnotationUastImpl(this, uAnnotation, tracker)
}

private typealias AnnotationValueVisitFunction = Annotation.Value.Visitor<*>.() -> Any?

private class IJAnnotationPsiImpl(
    lexicalScope: LexicalScope,
    private val psiAnnotation: PsiAnnotation,
    private val tracker: DependenciesTracker,
) : IJAnnotation(), LexicalScope by lexicalScope, HasDependencies by tracker {
    init {
        tracker.track(psiAnnotation)
    }

    private val valueCache = hashMapOf<String, Annotation.Value>()

    override val platformModel: PsiElement
        get() = psiAnnotation

    override val annotationClass: AnnotationDeclaration by plainLazy {
        AnnotationClassImpl.classOf(this, psiAnnotation).also(tracker::track)
    }

    override fun getValue(attribute: AnnotationDeclaration.Attribute): Annotation.Value {
        require(attribute is AnnotationClassImpl.AttributeImpl) { "Invalid attribute type" }
        return valueCache.getOrPut(attribute.name) {
            psiAnnotation.findDeclaredAttributeValue(attribute.name)?.let { value ->
                ValueImpl(
                    visit = makeVisitPsi(value, tracker)
                        .wrapIf(attribute.type.isArray()) { wrapIntoArrayIfNeeded(it, tracker) },
                    tracker = tracker,
                )
            } ?: attribute.defaultValue ?: ValueImpl(VisitUnresolved, tracker)
        }
    }
}

private abstract class IJAnnotationClassBase(
    lexicalScope: LexicalScope,
) : CtAnnotationDeclarationBase(), LexicalScope by lexicalScope, HasDependencies

private class UnresolvedAnnotationClass(
    lexicalScope: LexicalScope,
    override val qualifiedName: String,
) : IJAnnotationClassBase(lexicalScope), HasDependencies by UnresolvedDependencies {

    init {
        println("Unresolved: $qualifiedName")
    }

    override val annotations get() = emptySequence<Nothing>()
    override val attributes get() = emptySequence<Nothing>()
    override fun getRetention() = AnnotationRetention.RUNTIME
}


private class AnnotationClassImpl private constructor(
    lexicalScope: LexicalScope,
    val resolved: AnnotationClassId,
    private val tracker: DependenciesTracker = DependenciesTracker(),
) : IJAnnotationClassBase(lexicalScope),
    CtAnnotated by IJAnnotated(lexicalScope, resolved.clazz, PsiClass::toUClass, tracker),
    HasDependencies by tracker {
    init {
        tracker.track(resolved.clazz)
    }

    private val platformModel = resolved.clazz

    override val qualifiedName: String
        get() = resolved.qualifiedName

    private val _retention: AnnotationRetention by lazy {
        val retention = platformModel.annotations.find {
            it.hasQualifiedName("java.lang.annotation.Retention")
        }
        val reference = retention?.findAttributeValue("value") as? PsiReferenceExpression
        val value = reference?.resolve() as? PsiEnumConstant
        when(value?.name) {
            "SOURCE" -> AnnotationRetention.SOURCE
            "CLASS" -> AnnotationRetention.BINARY
            "RUNTIME" -> AnnotationRetention.RUNTIME
            else -> AnnotationRetention.BINARY
        }
    }

    override val attributes by plainLazy {
        platformModel.methods.asSequence()
            .filter { it.isAbstract() }
            .filterIsInstance<PsiAnnotationMethod>()
            .map { it -> AttributeImpl(it, tracker) }
            .memoize()
    }

    override fun getRetention(): AnnotationRetention = _retention

    inner class AttributeImpl(
        val platformModel: PsiAnnotationMethod,
        private val tracker: DependenciesTracker,
    ) : AnnotationDeclaration.Attribute {
        val defaultValue: Annotation.Value? by plainLazy {
            val visit = platformModel.defaultValue?.let { default ->
                if (default.isFromJava()) {
                    makeVisitPsi(default, tracker)
                } else {
                    makeVisitUExpression(default.toUElementOfType<UExpression>()!!, tracker)
                }
            } ?: VisitEmptyArray.takeIf {
                // Assume the default is an empty array.
                // WARNING: May be false positive if the annotation property is not `vararg`
                // TODO: Is there a way to known on Psi/Uast level if the property is a vararg?
                platformModel.language != JavaLanguage.INSTANCE && type.isArray()
            }
            visit?.let {
                ValueImpl(it, tracker)
            }
        }

        override val name: String
            get() = platformModel.name

        override val type: Type by plainLazy {
            (platformModel.returnType?.let { IJTypeImpl(it) }
                ?: CtErrorType(InvalidNameModel.Unresolved(null))).also(tracker::track)
        }
    }

    companion object : FactoryKey<AnnotationClassId, AnnotationClassImpl> {
        override fun LexicalScope.factory() = caching(::AnnotationClassImpl)

        fun classOf(
            lexicalScope: LexicalScope,
            annotation: UAnnotation,
        ): IJAnnotationClassBase {
            val clazz = annotation.resolve()
                ?: return UnresolvedAnnotationClass(lexicalScope, annotation.qualifiedName ?: annotation.toString())
            return with(lexicalScope) { Companion(AnnotationClassId(clazz)) }
        }

        fun classOf(
            lexicalScope: LexicalScope,
            annotation: PsiAnnotation,
        ): IJAnnotationClassBase {
            val clazz = annotation.resolveAnnotationType()
                ?: return UnresolvedAnnotationClass(lexicalScope, annotation.qualifiedName ?: annotation.toString())
            return with(lexicalScope) { Companion(AnnotationClassId(clazz)) }
        }
    }
}

private class ValueImpl(
    private val visit: AnnotationValueVisitFunction,
    private val tracker: DependenciesTracker,
) : ValueBase() {
    private val tracked by plainLazy {
        visit(ValueTrackingVisitor(tracker))
    }

    override fun <R> accept(visitor: Annotation.Value.Visitor<R>): R {
        tracked  // Triggers tracking
        @Suppress("UNCHECKED_CAST")
        return visit(visitor) as R
    }

    @Internal
    override val platformModel: Any?
        get() = null
}

private val VisitUnresolved = Annotation.Value.Visitor<*>::visitUnresolved

private val VisitEmptyArray: AnnotationValueVisitFunction = { visitArray(emptyList()) }

private class ValueTrackingVisitor(
    private val tracker: DependenciesTracker,
) : Annotation.Value.Visitor<Unit> {
    override fun visitDefault(value: Any?) = Unit
    override fun visitType(value: Type) = tracker.track(value)
    override fun visitAnnotation(value: Annotation) = Unit // Already tracked internally
    override fun visitEnumConstant(enum: Type, constant: String) = tracker.track(enum)
    override fun visitArray(value: List<Annotation.Value>) = value.forEach { it.accept(this) }
    override fun visitUnresolved() = tracker.track(UnresolvedDependencies)
}

private fun wrapIntoArrayIfNeeded(
    visit: AnnotationValueVisitFunction,
    tracker: DependenciesTracker,
): AnnotationValueVisitFunction {
    return { ->
        val wrapped = this
        object : Annotation.Value.Visitor<Any?> {
            override fun visitDefault(value: Any?) = wrapped.visitArray(listOf(ValueImpl(visit, tracker)))
            override fun visitArray(value: List<Annotation.Value>) = wrapped.visitArray(value)
        }.visit()
    }
}

private fun makeVisitLiteral(literal: Any?): AnnotationValueVisitFunction {
    return when(literal) {
        is Boolean -> { -> visitBoolean(literal) }
        is Byte -> { -> visitByte(literal) }
        is Short -> { -> visitShort(literal) }
        is Int -> { -> visitInt(literal) }
        is Long -> { -> visitLong(literal) }
        is Char -> { -> visitChar(literal) }
        is Float -> { -> visitFloat(literal) }
        is Double -> { -> visitDouble(literal) }
        is String -> { -> visitString(literal) }
        null -> {
            VisitUnresolved
        }
        else -> throw IllegalStateException("Not a literal: $literal")
    }
}

private fun LexicalScope.makeVisitPsi(
    psi: PsiAnnotationMemberValue,
    tracker: DependenciesTracker,
): AnnotationValueVisitFunction {
    return when (psi) {
        is PsiLiteral -> makeVisitLiteral(psi.value)
        is PsiAnnotation -> {
            val annotation = IJAnnotationUastImpl(this@makeVisitPsi, psi.toUElementOfType<UAnnotation>()!!, tracker);
            { -> visitAnnotation(annotation) }
        }
        is PsiClassObjectAccessExpression -> {
            val type = IJTypeImpl(psi.operand.type);
            { -> visitType(type) }
        }
        is PsiArrayInitializerMemberValue -> {
            // Class
            val elements: List<ValueImpl> = psi.initializers.map { element ->
                ValueImpl(makeVisitPsi(element, tracker), tracker)
            };
            { -> visitArray(elements) }
        }
        is PsiReferenceExpression -> {
            val enumConstant = psi.resolve() as? PsiEnumConstant ?: run {
                return psi.toUElementOfType<UExpression>()?.evaluate()?.let { makeVisitLiteral(it) }
                    ?: VisitUnresolved
            }
            val name = enumConstant.name
            val type = IJTypeImpl(enumConstant.type);
            { -> visitEnumConstant(type, name) }
        }
        else -> {
            psi.toUElementOfType<UExpression>()?.let {
                makeVisitUExpression(it, tracker)
            } ?: throw IllegalStateException("Unexpected psi: $psi")
        }
    }
}

private fun LexicalScope.makeVisitUExpression(
    expression: UExpression,
    tracker: DependenciesTracker,
): AnnotationValueVisitFunction {
    return when(expression) {
        is UCallExpression -> {
            when(expression.kind) {
                UastCallKind.NESTED_ARRAY_INITIALIZER -> {
                    val elements: List<ValueImpl> = expression.valueArguments.map { element ->
                        ValueImpl(makeVisitUExpression(element, tracker), tracker)
                    };
                    { -> visitArray(elements) }
                }
                UastCallKind.CONSTRUCTOR_CALL -> {
                    // Nested annotation
                    val nestedAnnotationVisit = expression.sourcePsi?.toUElementOfType<UAnnotation>()
                        ?: return VisitUnresolved
                    val nestedAnnotation =
                        IJAnnotationUastImpl(this@makeVisitUExpression, nestedAnnotationVisit, tracker);
                    { -> visitAnnotation(nestedAnnotation) }
                }
                else -> throw IllegalStateException("Unexpected call kind: ${expression.kind}")
            }
        }
        is UClassLiteralExpression -> {
            val type = IJTypeImpl(expression.type ?: return VisitUnresolved);
            { -> visitType(type) }
        }
        is UQualifiedReferenceExpression -> {
            val uEnumConstant: UVariable =
                expression.resolve()?.toUElementOfType<UEnumConstant>() ?: return run {
                    // This may be a constant reference
                    expression.evaluate()?.let { literal ->
                        return makeVisitLiteral(literal)
                    } ?: VisitUnresolved
                }
            val type = IJTypeImpl(uEnumConstant.type);
            val name = uEnumConstant.name ?: return VisitUnresolved
            { -> visitEnumConstant(type, name) }
        }
        else -> {
            expression.evaluate()?.let { literal ->
                return makeVisitLiteral(literal)
            }
            throw IllegalStateException("Unexpected uExpression: ${expression.asLogString()}")
        }
    }
}

private class AnnotationClassId private constructor(
    val qualifiedName: String,
    val locationId: String,
    // Extra:
    val clazz: PsiClass,
) {
    constructor(clazz: PsiClass): this(
        qualifiedName = clazz.qualifiedName!!,
        locationId = clazz.containingFile?.virtualFile?.path ?: clazz.hashCode().toString(),
        clazz = clazz,
    )

    private val hash by plainLazy { 31 * qualifiedName.hashCode() + locationId.hashCode() }

    override fun equals(other: Any?) = this === other || other is AnnotationClassId
            && qualifiedName == other.qualifiedName
            && locationId == other.locationId

    override fun hashCode() = hash

    override fun toString(): String = "Class [$qualifiedName]"
}