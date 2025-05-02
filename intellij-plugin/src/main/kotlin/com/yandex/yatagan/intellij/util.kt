package com.yandex.yatagan.intellij

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.yandex.yatagan.base.cast
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.graph.bindings.Binding
import com.yandex.yatagan.core.model.ClassBackedModel
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.lang.HasPlatformModel
import com.yandex.yatagan.lang.Type
import com.yandex.yatagan.lang.compiled.CtTypeBase
import com.yandex.yatagan.lang.compiled.CtTypeNameModel

internal fun BindingGraph.resolveBindingOrNull(node: NodeModel): Binding? = try {
    resolveBinding(node)
} catch (e: IllegalStateException) {
    null
}

internal fun Type.name(): CtTypeNameModel = this.cast<CtTypeBase>().nameModel


internal fun ClassBackedModel.name(): CtTypeNameModel {
    return type.name()
}

internal fun PsiElement.asSmartPointer(): SmartPsiElementPointer<PsiElement> {
    return SmartPointerManager.createPointer(this)
}

internal fun ClassBackedModel.getSourceSmartPointer(): SmartPsiElementPointer<PsiElement>? {
    val element = type.declaration.platformModel as? PsiClass
    return (element?.nameIdentifier ?: element?.lBrace)?.asSmartPointer()
}

internal fun HasPlatformModel.getPsiElement(): PsiElement? {
    return platformModel as? PsiElement
}
