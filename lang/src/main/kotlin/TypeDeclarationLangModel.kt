package com.yandex.daggerlite.core.lang

/**
 * Models a type declaration. Can represent class/primitive/array/... types.
 */
interface TypeDeclarationLangModel : AnnotatedLangModel, HasPlatformModel, Accessible {
    /**
     * Whether the declaration is an interface.
     */
    val isInterface: Boolean

    /**
     * Whether the declaration is abstract (abstract class or interface).
     */
    val isAbstract: Boolean

    /**
     * Whether the declaration is a kotlin `object`.
     */
    val kotlinObjectKind: KotlinObjectKind?

    /**
     * Qualified/Canonical name of the represented class from the Java point of view.
     *
     * Example: `"com.example.TopLevel.Nested"`.
     */
    val qualifiedName: String

    /**
     * If this declaration is nested, returns enclosing declaration. `null` otherwise.
     */
    val enclosingType: TypeDeclarationLangModel?

    /**
     * Interfaces directly implemented/extended by the declaration.
     */
    val interfaces: Sequence<TypeLangModel>

    /**
     * Super-type, if present.
     *
     * NOTE: Never returns `java.lang.Object`/`kotlin.Any`, `null` is returned instead.
     * This is done to counter uniformity issues.
     */
    val superType: TypeLangModel?

    /**
     * All declared non-private constructors.
     */
    val constructors: Sequence<ConstructorLangModel>

    /**
     * All non-private functions (including static and inherited ones).
     *
     * All returned functions (including inherited or overridden ones) have [owner][FunctionLangModel.owner] defined
     * as `this`.
     *
     * Never includes functions defined in `java.lang.Object`/`kotlin.Any`, as they are of no interest to DL.
     */
    val functions: Sequence<FunctionLangModel>

    /**
     * All non-private declared fields (including static and inherited).
     */
    val fields: Sequence<FieldLangModel>

    /**
     * Nested non-private classes that are declared inside this declaration.
     */
    val nestedClasses: Sequence<TypeDeclarationLangModel>

    /**
     * Kotlin's default companion object declaration, if one exists for the type.
     * If a companion object has non-default name (`"Companion"`), it won't be found here.
     */
    val defaultCompanionObjectDeclaration: TypeDeclarationLangModel?

    /**
     * Creates [TypeLangModel] based on the declaration **assuming, that no type arguments are required**.
     * If the declaration does require type arguments, the behavior is undefined.
     */
    fun asType(): TypeLangModel

    /**
     * [com.yandex.daggerlite.Component] annotation if present.
     */
    val componentAnnotationIfPresent: ComponentAnnotationLangModel?

    /**
     * [com.yandex.daggerlite.Module] annotation if present.
     */
    val moduleAnnotationIfPresent: ModuleAnnotationLangModel?

    /**
     * Aggregated [com.yandex.daggerlite.Condition], ... etc. annotations.
     */
    val conditions: Sequence<ConditionLangModel>

    /**
     * [com.yandex.daggerlite.Conditional] annotations. If none present, the sequence is empty.
     */
    val conditionals: Sequence<ConditionalAnnotationLangModel>

    /**
     * [com.yandex.daggerlite.ComponentFlavor] annotation if present.
     */
    val componentFlavorIfPresent: ComponentFlavorAnnotationLangModel?
}