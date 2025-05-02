package com.yandex.yatagan.intellij.ui

import com.intellij.openapi.util.IconLoader

object Icons {
    val RootComponent = IconLoader.getIcon("/icons/class.svg", Icons::class.java)

    val Module = IconLoader.getIcon("/icons/module.svg", Icons::class.java)
    val ModuleStatic = IconLoader.getIcon("/icons/module-static.svg", Icons::class.java)
    val Binding = IconLoader.getIcon("/icons/binding-generic.svg", Icons::class.java)

    val GroupByModule = icon("/icons/groupByModule.svg")

    val Provision = icon("/icons/bindings/provides.svg")
    val ProvisionConditional = icon("/icons/bindings/provides-cond.svg")
    val Inject = icon("/icons/bindings/inject.svg")
    val InjectConditional = icon("/icons/bindings/inject-cond.svg")
    val Alias = icon("/icons/bindings/alias.svg")
    val Alternatives = icon("/icons/bindings/binds-alternatives.svg")
    val AssistedInject = icon("/icons/bindings/assisted-inject.svg")
    val AssistedInjectConditional = icon("/icons/bindings/assisted-inject-cond.svg")
    val Builtin = icon("/icons/bindings/builtin.svg")
    val ExplicitEmpty = icon("/icons/bindings/empty.svg")
    val Missing = icon("/icons/bindings/missing.svg")
    val Instance = icon("/icons/bindings/binds-instance.svg")
    val Map = icon("/icons/bindings/map.svg")
    val List = icon("/icons/bindings/list.svg")
    val Set = icon("/icons/bindings/set.svg")

    val Lazy = icon("/icons/bindings/lazy.svg")
    val Provider = icon("/icons/bindings/provider.svg")
    val Direct = icon("/icons/bindings/direct.svg")

    private fun icon(path: String) = IconLoader.getIcon(path, Icons::class.java)
}