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

package com.yandex.yatagan.core.graph.impl.bindings

import com.yandex.yatagan.base.ListComparator
import com.yandex.yatagan.base.MapComparator
import com.yandex.yatagan.core.graph.BindingGraph
import com.yandex.yatagan.core.graph.bindings.Binding
import com.yandex.yatagan.core.graph.bindings.MultiBinding
import com.yandex.yatagan.core.graph.impl.topologicalSort
import com.yandex.yatagan.core.model.CollectionTargetKind
import com.yandex.yatagan.core.model.ModuleHostedBindingModel
import com.yandex.yatagan.core.model.MultiBindingDeclarationModel
import com.yandex.yatagan.core.model.NodeModel
import com.yandex.yatagan.lang.HasPlatformModel
import com.yandex.yatagan.validation.MayBeInvalid
import com.yandex.yatagan.validation.format.TextColor
import com.yandex.yatagan.validation.format.append
import com.yandex.yatagan.validation.format.appendRichString
import com.yandex.yatagan.validation.format.bindingModelRepresentation
import com.yandex.yatagan.validation.format.buildRichString

internal class MultiBindingImpl(
    override val owner: BindingGraph,
    override val target: NodeModel,
    override val upstream: MultiBindingImpl?,
    override val targetForDownstream: NodeModel,
    override val kind: CollectionTargetKind,
    contributions: List<Contribution>,
) : MultiBinding, BindingDefaultsMixin, ComparableBindingMixin<MultiBindingImpl> {
    private val _contributions = contributions

    data class Contribution(
        override val contributionDependency: NodeModel,
        override val origin: ModuleHostedBindingModel,
        override val contributionType: MultiBinding.ContributionType,
    ) : Comparable<Contribution>, MultiBinding.Contribution {
        override fun compareTo(other: Contribution): Int {
            return origin.method.compareTo(other.origin.method)
        }
    }

    override val contributions: Collection<Contribution> by lazy {
        when (kind) {
            CollectionTargetKind.List -> {
                // Resolve aliases as multi-bindings often work with @Binds
                val resolved: Map<NodeModel, Contribution> = _contributions.associateBy { contribution ->
                    owner.resolveBinding(contribution.contributionDependency).target
                }
                topologicalSort(
                    nodes = resolved.keys,
                    inside = owner,
                ).map(resolved::getValue)
            }

            CollectionTargetKind.Set -> {
                _contributions
            }
        }
    }

    override val dependencies get() = extensibleAwareDependencies(
        _contributions.map { it.contributionDependency })

    override fun toString(childContext: MayBeInvalid?) = bindingModelRepresentation(
        modelClassName = when(kind) {
            CollectionTargetKind.List -> "list-binding"
            CollectionTargetKind.Set -> "set-binding"
        },
        childContext = childContext,
        representation = { append("List ") },
        childContextTransform = { dependency ->
            when (dependency.node) {
                upstream?.targetForDownstream -> "<inherited from parent component>"
                else -> dependency
            }
        },
        ellipsisStatistics = {_, dependencies ->
            var elements = 0
            var collections = 0
            var mentionUpstream = false
            for (dependency in dependencies) when(contributions.find { it.contributionDependency == dependency}?.contributionType) {
                MultiBinding.ContributionType.Element -> elements++
                MultiBinding.ContributionType.Collection -> collections++
                null -> mentionUpstream = (dependency.node == upstream?.targetForDownstream)
            }
            sequenceOf(
                when(elements) {
                    0 -> null
                    1 -> "1 element"
                    else -> "$elements elements"
                },
                when(collections) {
                    0 -> null
                    1 -> "1 collection"
                    else -> "$collections collections"
                },
                if (mentionUpstream) "upstream" else null,
            ).filterNotNull().joinTo(this, separator = " + ")
        },
        openBracket = " { ",
        closingBracket = buildRichString {
            append(" } ")
            appendRichString {
                color = TextColor.Gray
                append("assembled in ")
            }
            append(owner)
        },
    )

    override fun compareTo(other: MultiBindingImpl): Int {
        return ListComparator.ofComparable<Contribution>(asSorted = false)
            .compare(_contributions, other._contributions)
    }

    override fun <R> accept(visitor: Binding.Visitor<R>): R {
        return visitor.visitMulti(this)
    }

    override val langModel: HasPlatformModel?
        get() = null
}