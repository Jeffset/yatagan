/*
 * Copyright 2022 Yandex LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yandex.yatagan.rt.engine

import com.yandex.yatagan.rt.support.DynamicValidationDelegate
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

internal inline fun <R> DynamicValidationDelegate.Promise?.awaitOnError(block: () -> R): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    this ?: return block()
    try {
        return block()
    } catch (e: PossiblyInvalidGraphException) {
        // No need to await or wrap anything - already done, as blocks can be nested
        throw e
    } catch (e: Exception) {
        // Await validation, to correctly display the error
        await()
        throw PossiblyInvalidGraphException(e)
    }
}

internal class PossiblyInvalidGraphException(
    cause: Exception,
): RuntimeException("Graph is possibly invalid/had invalid inputs", cause)