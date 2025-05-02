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

package com.yandex.yatagan.core.graph.bindings

import com.yandex.yatagan.base.api.StableForImplementation
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.model.ModuleHostedBindingModel
import com.yandex.yatagan.core.model.ModuleModel
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.validation.MayBeInvalid

/**
 * Represents a way to provide a [NodeModel].
 * Each [NodeModel] must have a single [BaseBinding] for a [BindingGraph] to be valid.
 */
public interface BaseBinding : MayBeInvalid, Comparable<BaseBinding> {
    /**
     * A node that this binding provides.
     */
    public val target: NodeModel

    /**
     * A graph which hosts the binding.
     */
    public val owner: BindingGraph

    /**
     * If binding came from a [ModuleModel] then this is it.
     * If it's intrinsic - `null` is returned.
     */
    public val originModule: ModuleModel?

    public val methodModel: ModuleHostedBindingModel?

    public fun <R> accept(visitor: Visitor<R>): R

    @StableForImplementation
    public interface Visitor<R> {
        public fun visitOther(other: BaseBinding): R
        public fun visitAlias(alias: AliasBinding): R = visitOther(alias)
        public fun visitBinding(binding: Binding): R = visitOther(binding)
    }
}