package com.yandex.yatagan.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.psi.PsiFile
import com.intellij.uast.UastMetaLanguage

class YataganActionsGroup : DefaultActionGroup() {
    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun update(e: AnActionEvent) {
        val file: PsiFile? = e.dataContext.getData(CommonDataKeys.PSI_FILE)

        e.presentation.isEnabledAndVisible = file != null &&
                UastMetaLanguage.findLanguageByID(file.language.id) === file.language
    }
}