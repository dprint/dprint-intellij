package com.dprint.services.editorservice.process

import com.dprint.config.UserConfiguration
import com.dprint.utils.getValidConfigPath
import com.dprint.utils.getValidExecutablePath
import com.dprint.utils.infoLogWithConsole
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import io.kotest.core.spec.style.FunSpec
import io.mockk.EqMatcher
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.verify
import java.io.File
import java.util.concurrent.CompletableFuture

class EditorProcessTest :
    FunSpec({
        mockkStatic(ProcessHandle::current)
        mockkStatic(::infoLogWithConsole)
        mockkStatic("com.dprint.utils.FileUtilsKt")

        val project = mockk<Project>()
        val processHandle = mockk<ProcessHandle>()
        val process = mockk<Process>()
        val userConfig = mockk<UserConfiguration>()

        val editorProcess = EditorProcess(project)

        beforeEach {
            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { project.service<UserConfiguration>() } returns userConfig
        }

        afterEach {
            clearAllMocks()
        }

        test("it creates a process with the correct args") {
            val execPath = "/bin/dprint"
            val configPath = "./dprint.json"
            val workingDir = "/working/dir"
            val parentProcessId = 1L

            mockkConstructor(GeneralCommandLine::class)
            mockkConstructor(File::class)
            mockkConstructor(StdErrListener::class)

            every { getValidExecutablePath(project) } returns execPath
            every { getValidConfigPath(project) } returns configPath

            every { ProcessHandle.current() } returns processHandle
            every { processHandle.pid() } returns parentProcessId
            every { userConfig.state } returns UserConfiguration.State()
            every { constructedWith<File>(EqMatcher(configPath)).parent } returns workingDir
            every { process.pid() } returns 2L
            every { process.onExit() } returns CompletableFuture.completedFuture(process)
            every { anyConstructed<StdErrListener>().listen() } returns Unit

            val expectedArgs =
                listOf(
                    execPath,
                    "editor-service",
                    "--config",
                    configPath,
                    "--parent-pid",
                    parentProcessId.toString(),
                    "--verbose",
                )

            // This essentially tests the correct args are passed in.
            every { constructedWith<GeneralCommandLine>(EqMatcher(expectedArgs)).createProcess() } returns process

            editorProcess.initialize()

            verify(
                exactly = 1,
            ) { constructedWith<GeneralCommandLine>(EqMatcher(expectedArgs)).withWorkDirectory(workingDir) }
            verify(exactly = 1) { constructedWith<GeneralCommandLine>(EqMatcher(expectedArgs)).createProcess() }
        }
    })
