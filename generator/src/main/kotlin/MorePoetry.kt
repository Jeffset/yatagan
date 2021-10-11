// Copyright 2020 Yandex LLC. All rights reserved.

package com.yandex.dagger3.generator

import com.squareup.javapoet.AnnotationSpec
import com.squareup.javapoet.ArrayTypeName
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.CodeBlock
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.TypeVariableName
import com.yandex.dagger3.core.CallableNameModel
import com.yandex.dagger3.core.ConstructorNameModel
import com.yandex.dagger3.core.FunctionNameModel
import com.yandex.dagger3.core.MemberCallableNameModel
import com.yandex.dagger3.core.NameModel
import com.yandex.dagger3.core.NodeDependency
import com.yandex.dagger3.core.NodeModel
import com.yandex.dagger3.core.PropertyNameModel
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.Name
import kotlin.reflect.KClass

@DslMarker
internal annotation class JavaPoetry

internal open class ExpressionBuilder {
    val impl: CodeBlock.Builder = CodeBlock.builder()

    operator fun CodeBlock.unaryPlus() {
        impl.add(this)
    }

    operator fun String.unaryPlus() {
        impl.add(this)
    }

    inline fun <T> join(
        seq: Sequence<T>,
        separator: String = ", ",
        crossinline block: ExpressionBuilder.(T) -> Unit
    ) {
        impl.add(CodeBlock.join(seq.map { buildExpression { block(it) } }.asIterable(), separator))
    }

    fun String.formatCode(vararg args: Any): CodeBlock {
        return CodeBlock.of(this.replace('%', '$'), *args)
    }
}

@JavaPoetry
internal open class CodeBuilder {
    val implCode: CodeBlock.Builder = CodeBlock.builder()

    operator fun CodeBlock.unaryPlus() {
        implCode.addStatement(this)
    }

    operator fun String.unaryPlus() {
        implCode.addStatement(this)
    }

    fun String.formatCode(vararg args: Any): CodeBlock {
        return CodeBlock.of(this.replace('%', '$'), *args)
    }

    inline fun controlFlow(code: CodeBlock, block: CodeBuilder.() -> Unit) {
        implCode.beginControlFlow("\$L", code)
        block()
        implCode.endControlFlow()
    }

    inline fun controlFlow(code: String, block: CodeBuilder.() -> Unit) {
        implCode.beginControlFlow("\$L", code)
        block()
        implCode.endControlFlow()
    }
}

internal interface AnnotatibleBuilder {
    fun annotation(mirror: AnnotationMirror)

    fun annotation(clazz: KClass<out Annotation>, block: AnnotationSpecBuilder.() -> Unit)
}

@JavaPoetry
internal abstract class MethodSpecBuilder : CodeBuilder(), AnnotatibleBuilder {
    abstract val impl: MethodSpec.Builder

    fun implBuild(): MethodSpec {
        impl.addCode(implCode.build())
        return impl.build()
    }

    inline fun parameter(
        type: TypeName, name: String,
        block: ParameterSpecBuilder.() -> Unit = {}
    ) {
        impl.addParameter(ParameterSpecBuilder(type, name).apply(block).impl.build())
    }

    inline fun parameter(type: TypeName, name: Name, block: ParameterSpecBuilder.() -> Unit = {}) {
        parameter(type, name.toString(), block)
    }

    inline fun <reified A : Annotation> annotation(block: AnnotationSpecBuilder.() -> Unit = {}) {
        impl.addAnnotation(AnnotationSpecBuilder(A::class).apply(block).impl.build())
    }

    fun annotation(name: ClassName) {
        impl.addAnnotation(name)
    }

    override fun annotation(mirror: AnnotationMirror) {
        impl.addAnnotation(AnnotationSpec.get(mirror))
    }

    override fun annotation(
        clazz: KClass<out Annotation>,
        block: AnnotationSpecBuilder.() -> Unit,
    ) {
        impl.addAnnotation(AnnotationSpecBuilder(clazz).apply(block).impl.build())
    }

    fun returnType(type: TypeName) {
        impl.returns(type)
    }

    fun modifiers(vararg modifiers: Modifier) {
        impl.addModifiers(*modifiers)
    }

    inline fun defaultValue(block: ExpressionBuilder.() -> Unit) {
        impl.defaultValue(buildExpression(block))
    }
}

@JavaPoetry
internal class FieldSpecBuilder(type: TypeName, name: String) {
    val impl: FieldSpec.Builder = FieldSpec.builder(type, name)

    fun modifiers(vararg modifiers: Modifier) {
        impl.addModifiers(*modifiers)
    }

    inline fun initializer(block: ExpressionBuilder.() -> Unit) {
        impl.initializer(buildExpression(block))
    }

    fun initializer(code: CodeBlock) {
        impl.initializer(code)
    }
}

@JavaPoetry
internal class ParameterSpecBuilder constructor(type: TypeName, name: String) : AnnotatibleBuilder {
    val impl: ParameterSpec.Builder = ParameterSpec.builder(type, name)

    inline fun <reified A : Annotation> annotation(
        block: AnnotationSpecBuilder.() -> Unit = {}
    ) {
        impl.addAnnotation(AnnotationSpecBuilder(A::class).apply(block).impl.build())
    }

    override fun annotation(mirror: AnnotationMirror) {
        impl.addAnnotation(AnnotationSpec.get(mirror))
    }

    override fun annotation(
        clazz: KClass<out Annotation>,
        block: AnnotationSpecBuilder.() -> Unit,
    ) {
        impl.addAnnotation(AnnotationSpecBuilder(clazz).apply(block).impl.build())
    }
}

@JavaPoetry
internal abstract class TypeSpecBuilder : AnnotatibleBuilder {
    abstract val impl: TypeSpec.Builder

    inline fun method(name: String, block: MethodSpecBuilder.() -> Unit) {
        impl.addMethod(MethodSpecBuilderImpl(name).apply(block).implBuild())
    }

    inline fun constructor(block: MethodSpecBuilder.() -> Unit) {
        impl.addMethod(ConstructorSpecBuilder().apply(block).implBuild())
    }

    inline fun override(method: ExecutableElement, block: MethodSpecBuilder.() -> Unit) {
        impl.addMethod(OverrideMethodSpecBuilder(method).apply(block).implBuild())
    }

    inline fun field(type: TypeName, name: String, block: FieldSpecBuilder.() -> Unit = {}) {
        impl.addField(FieldSpecBuilder(type, name).apply(block).impl.build())
    }

    inline fun <reified A : Annotation> annotation(block: AnnotationSpecBuilder.() -> Unit = {}) {
        impl.addAnnotation(AnnotationSpecBuilder(A::class).apply(block).impl.build())
    }

    override fun annotation(mirror: AnnotationMirror) {
        impl.addAnnotation(AnnotationSpec.get(mirror))
    }

    override fun annotation(
        clazz: KClass<out Annotation>,
        block: AnnotationSpecBuilder.() -> Unit,
    ) {
        impl.addAnnotation(AnnotationSpecBuilder(clazz).apply(block).impl.build())
    }

    fun annotation(name: ClassName) {
        impl.addAnnotation(name)
    }

    fun implements(typeName: TypeName) {
        impl.addSuperinterface(typeName)
    }

    fun extends(typeName: TypeName) {
        impl.superclass(typeName)
    }

    fun originatingElement(element: Element) {
        impl.addOriginatingElement(element)
    }

    inline fun nestedType(block: () -> TypeSpec) {
        impl.addType(block())
    }

    fun modifiers(vararg modifiers: Modifier) {
        impl.addModifiers(*modifiers)
    }

    fun generic(vararg typeVars: TypeVariableName) {
        typeVars.forEach(impl::addTypeVariable)
    }
}

@JavaPoetry
internal class AnnotationSpecBuilder(
    clazz: KClass<out Annotation>
) {
    val impl: AnnotationSpec.Builder = AnnotationSpec.builder(clazz.java)

    inline fun <reified E : Enum<E>> enumValue(value: E, name: String = "value") {
        impl.addMember(name, "\$T.\$N", E::class.java, value.name)
    }

    fun classValue(type: ClassName, name: String = "value") {
        impl.addMember(name, "\$T.class", type)
    }

    fun classValues(vararg types: ClassName, name: String = "value") {
        value(name) {
            +"{"
            join(types.asSequence()) { type -> +"%T.class".formatCode(type) }
            +"}"
        }
    }

    inline fun value(name: String = "value", block: ExpressionBuilder.() -> Unit) {
        impl.addMember(name, buildExpression(block))
    }

    fun stringValue(value: String, name: String = "value") {
        impl.addMember(name, buildExpression { +"%S".formatCode(value) })
    }

    fun stringValues(vararg values: String, name: String = "value") {
        value(name) {
            +"{"
            join(values.asSequence()) { value -> +"%S".formatCode(value) }
            +"}"
        }
    }

    inline fun <reified A : Annotation> annotation(
        block: AnnotationSpecBuilder.() -> Unit = {}
    ): AnnotationSpec {
        return AnnotationSpecBuilder(A::class).apply(block).impl.build()
    }
}

internal class ClassTypeSpecBuilder(name: ClassName) : TypeSpecBuilder() {
    override val impl: TypeSpec.Builder = TypeSpec.classBuilder(name)
}

internal class InterfaceTypeSpecBuilder(name: ClassName) : TypeSpecBuilder() {
    override val impl: TypeSpec.Builder = TypeSpec.interfaceBuilder(name)
}

internal class AnnotationTypeSpecBuilder(name: ClassName) : TypeSpecBuilder() {
    override val impl: TypeSpec.Builder = TypeSpec.annotationBuilder(name)
}

internal class MethodSpecBuilderImpl(name: String) : MethodSpecBuilder() {
    override val impl: MethodSpec.Builder = MethodSpec.methodBuilder(name)
}

internal class ConstructorSpecBuilder : MethodSpecBuilder() {
    override val impl: MethodSpec.Builder = MethodSpec.constructorBuilder()
}

internal class OverrideMethodSpecBuilder(base: ExecutableElement) : MethodSpecBuilder() {
    override val impl: MethodSpec.Builder = MethodSpec.overriding(base)
}

internal inline fun buildInterface(
    name: ClassName, block: TypeSpecBuilder.() -> Unit
): TypeSpec = InterfaceTypeSpecBuilder(name).apply(block).impl.build()

internal inline fun buildAnnotationClass(
    name: ClassName, block: TypeSpecBuilder.() -> Unit
): TypeSpec = AnnotationTypeSpecBuilder(name).apply(block).impl.build()

internal inline fun buildClass(
    name: ClassName, block: TypeSpecBuilder.() -> Unit
): TypeSpec = ClassTypeSpecBuilder(name).apply(block).impl.build()

internal inline fun buildExpression(block: ExpressionBuilder.() -> Unit): CodeBlock {
    return ExpressionBuilder().apply(block).impl.build()
}

internal inline fun NameModel.asClassName(
    transformName: (String) -> String,
): ClassName {
    require(typeArguments.isEmpty())
    return ClassName.get(packageName, transformName(simpleName))
}

internal fun NameModel.asTypeName(): TypeName {
    val className = ClassName.get(packageName, simpleName)
    return if (typeArguments.isNotEmpty()) {
        ParameterizedTypeName.get(className, *typeArguments.map(NameModel::asTypeName).toTypedArray())
    } else className
}

internal fun NodeDependency.asTypeName(): TypeName {
    val typeName = node.name.asTypeName()
    return when (kind) {
        NodeDependency.Kind.Normal -> typeName
        NodeDependency.Kind.Lazy -> ParameterizedTypeName.get(Names.Lazy, typeName)
        NodeDependency.Kind.Provider -> ParameterizedTypeName.get(Names.Provider, typeName)
    }
}

internal fun NodeModel.asIdentifier() = name.qualifiedName.replace('.', '_') + (qualifier ?: "")

internal fun MemberCallableNameModel.functionName() = when (this) {
    is FunctionNameModel -> function
    is PropertyNameModel -> "get${property.capitalize()}"
}

internal fun ExpressionBuilder.call(name: CallableNameModel, arguments: Sequence<Any>) {
    when (name) {
        is ConstructorNameModel -> +"new %T(".formatCode(name.type.asTypeName())
        is MemberCallableNameModel -> +"%T.%N(".formatCode(
            name.ownerName.asTypeName(),
            name.functionName(),
        )
    }
    join(arguments.asSequence()) { arg ->
        +"%L".formatCode(arg)
    }
    +")"
}

internal operator fun ClassName.invoke(name: TypeName): ParameterizedTypeName {
    return ParameterizedTypeName.get(this, name)
}

internal object ArrayName {
    internal operator fun get(name: TypeName): ArrayTypeName {
        return ArrayTypeName.of(name)
    }
}

internal object Names {
    val Lazy: ClassName = ClassName.get(dagger.Lazy::class.java)
    val Provider: ClassName = ClassName.get(javax.inject.Provider::class.java)
    val AssertionError = ClassName.get(java.lang.AssertionError::class.java)
}