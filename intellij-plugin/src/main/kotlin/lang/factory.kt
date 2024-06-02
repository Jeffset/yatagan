package com.yandex.yatagan.intellij.lang

import com.yandex.yatagan.lang.TypeDeclaration
import org.jetbrains.uast.UClass

fun TypeDeclaration(uClass: UClass): TypeDeclaration {
    return IJTypeDeclarationImpl(uClass.javaPsi)
}