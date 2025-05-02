package com.yandex.yatagan.intellij.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

class ToolWindowFactoryImpl : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
//        toolWindow.title = "Yatagan"
//        toolWindow.stripeTitle = "Yatagan"

        val componentsPanel = YataganComponentsToolWindowPanel(project, toolWindow)
        with(toolWindow.contentManager) {
            val content = factory.createContent(componentsPanel, componentsPanel.title, false)
            addContent(content)
        }
    }
}