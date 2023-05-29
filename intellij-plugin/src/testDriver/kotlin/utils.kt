package com.yandex.yatagan.intellij.testing

import org.jetbrains.uast.UClass
import org.jetbrains.uast.UFile

internal fun UFile.allClasses(): Array<UClass> {
    return classes.toTypedArray() + classes.flatMap { it.allInnerClasses().toList() }
}

internal fun UClass.allInnerClasses(): Array<UClass> {
    return innerClasses + innerClasses.flatMap { it.allInnerClasses().toList() }
}