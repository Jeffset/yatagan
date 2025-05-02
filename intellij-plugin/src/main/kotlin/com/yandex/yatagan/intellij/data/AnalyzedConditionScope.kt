package com.yandex.yatagan.intellij.data

import com.yandex.yatagan.base.api.Incubating
import com.yandex.yatagan.core.model.ConditionScope
import com.yandex.yatagan.validation.format.toString

@OptIn(Incubating::class)
class AnalyzedConditionScope(
    val expression: String,
) {
    constructor(scope: ConditionScope?) : this(scope?.toString(null)?.toString() ?: "<unresolved>")
}