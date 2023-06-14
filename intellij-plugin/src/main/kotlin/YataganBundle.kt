package com.yandex.yatagan.intellij

import com.intellij.AbstractBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

internal object YataganBundle : AbstractBundle(BUNDLE) {
    @Nls
    fun message(
        @NonNls @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ) = getMessage(key, params)
}

private const val BUNDLE = "messages.YataganBundle"