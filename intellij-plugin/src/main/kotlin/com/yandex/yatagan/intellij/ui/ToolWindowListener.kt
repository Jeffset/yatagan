package com.yandex.yatagan.intellij.ui

import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.yandex.yatagan.intellij.services.LexicalScopeService

class ToolWindowListener : ToolWindowManagerListener {
    override fun toolWindowShown(toolWindow: ToolWindow) {
        if (toolWindow.id != "Yatagan") {
            return
        }

        // Touch the service to trigger its indexing
        LexicalScopeService.getInstance(toolWindow.project)
    }
}