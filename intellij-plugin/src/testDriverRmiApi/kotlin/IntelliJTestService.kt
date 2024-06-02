package com.yandex.yatagan.intellij.testing.rmi

import java.rmi.Remote
import java.rmi.RemoteException

interface IntelliJTestService : Remote {
    @Throws(RemoteException::class)
    fun runTest(
        projectDirectory: String,
    ): String

    companion object {
        const val RMI_PORT = 4228
    }
}