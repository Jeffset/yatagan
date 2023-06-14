package com.yandex.yatagan.intellij

import com.intellij.openapi.project.Project
import com.yandex.yatagan.base.ObjectCacheRegistry
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.intellij.lang.ProcessingUtils
import com.yandex.yatagan.lang.BuiltinAnnotation
import com.yandex.yatagan.lang.TypeDeclaration
import kotlin.reflect.KClass

annotation class Foo(val value: KClass<*>, vararg val foo: Int, val quu: Short = 2) {
}

@Foo(Int::class, 3, 4)
internal inline fun <R> withYataganCacheScope(root: Project, block: () -> R): R {
    return ObjectCacheRegistry.use {
        ProcessingUtils(root).use {
            block()
        }
    }
}


internal fun TypeDeclaration.isComponent(): Boolean = getAnnotation(BuiltinAnnotation.Component) != null

internal fun TypeDeclaration.isModule(): Boolean = getAnnotation(BuiltinAnnotation.Module) != null

internal fun ModuleModel.allIncludes(): Set<ModuleModel> {
    val allModules = mutableSetOf<ModuleModel>()
    val moduleQueue: ArrayDeque<ModuleModel> = ArrayDeque(includes)
    while (moduleQueue.isNotEmpty()) {
        val module = moduleQueue.removeFirst()
        if (!allModules.add(module)) {
            continue
        }
        moduleQueue += module.includes
    }
    return allModules
}