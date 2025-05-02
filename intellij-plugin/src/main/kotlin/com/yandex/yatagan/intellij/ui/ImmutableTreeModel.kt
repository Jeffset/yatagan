package com.yandex.yatagan.intellij.ui

import com.intellij.util.ui.tree.AbstractTreeModel
import com.yandex.yatagan.base.cast
import javax.swing.tree.TreePath

class ImmutableMultiNode<T, M>(
    val value: T,
    val multiChildren: Map<M, List<ImmutableMultiTreeModel<T, M>>>,
)

class ImmutableMultiTreeModel<T, M>(
    val root: ImmutableMultiNode<T, M>,
    mode: M,
) : AbstractTreeModel() {
    var mode: M = mode
        set(value) {
            if (field == value)
                return
            field = value
            treeStructureChanged(TreePath(root), null, null)
        }

    override fun getRoot() = root
    override fun getChild(node: Any, index: Int) = childrenOf(node.cast())[index]
    override fun getChildCount(node: Any) = childrenOf(node.cast()).size
    override fun isLeaf(node: Any) = childrenOf(node.cast()).isEmpty()
    override fun getIndexOfChild(node: Any, child: Any) = childrenOf(node.cast()).indexOf(child)

    private fun childrenOf(node: ImmutableMultiNode<T, M>) =
        node.multiChildren[mode] ?: emptyList()
}