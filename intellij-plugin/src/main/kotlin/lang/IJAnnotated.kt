package com.yandex.yatagan.intellij.lang

import com.intellij.psi.PsiModifierListOwner
import com.yandex.yatagan.base.memoize
import com.yandex.yatagan.lang.compiled.CtAnnotated
import com.yandex.yatagan.lang.compiled.CtAnnotationBase
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget

internal class IJAnnotated(
    private val impl: PsiModifierListOwner,
) : CtAnnotated {
    override val annotations: Sequence<CtAnnotationBase> by lazy {
        val result = impl.detectKotlinOriginWithUseSiteTarget()
            ?: return@lazy impl.annotations.asSequence().map { IJAnnotationImpl(it) }.memoize()

        val (kotlinImpl, relation) = result
//        println("For ${impl.text} the relation was $relation")
        val annotationEntries = kotlinImpl.annotationEntries
            .filter {
                when(it.useSiteTarget?.getAnnotationUseSiteTarget()) {
                    AnnotationUseSiteTarget.FIELD -> relation == KotlinToJavaRelation.Field
                    AnnotationUseSiteTarget.FILE -> TODO()
                    AnnotationUseSiteTarget.PROPERTY -> false  // always skip property targets
                    AnnotationUseSiteTarget.PROPERTY_GETTER -> relation == KotlinToJavaRelation.PropertyGetter
                    AnnotationUseSiteTarget.PROPERTY_SETTER -> relation == KotlinToJavaRelation.PropertySetter
                    AnnotationUseSiteTarget.RECEIVER -> relation == KotlinToJavaRelation.Receiver
                    AnnotationUseSiteTarget.CONSTRUCTOR_PARAMETER -> relation == KotlinToJavaRelation.ConstructorParameter
                    AnnotationUseSiteTarget.SETTER_PARAMETER -> relation == KotlinToJavaRelation.SetterParameter
                    AnnotationUseSiteTarget.PROPERTY_DELEGATE_FIELD -> false
                    null -> relation == KotlinToJavaRelation.Direct
                }
            }

        annotationEntries.asSequence().map {
            IJAnnotationImpl.LazyImplForKt(it, impl)
        }.memoize()
    }

    override fun <A : Annotation> isAnnotatedWith(type: Class<A>): Boolean {
        return impl.hasAnnotation(type.canonicalName)
    }
}