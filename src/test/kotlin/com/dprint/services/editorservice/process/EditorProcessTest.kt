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

        beforeEach {
            every { infoLogWithConsole(any(), project, any()) } returns Unit
            every { project.service<UserConfiguration>() } returns userConfig
        }

        afterEach {
            clearAllMocks()
        }

        test("it creates a process with the correct args") {
            val editorProcess = EditorProcess(project)
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

        test("isAlive returns true after initialization") {
            val editorProcess = EditorProcess(project)
            val execPath = "/bin/dprint"
            val configPath = "./dprint.json"
            val workingDir = "/working/dir"

            mockkConstructor(GeneralCommandLine::class)
            mockkConstructor(File::class)
            mockkConstructor(StdErrListener::class)

            every { getValidExecutablePath(project) } returns execPath
            every { getValidConfigPath(project) } returns configPath
            every { ProcessHandle.current() } returns processHandle
            every { processHandle.pid() } returns 1L
            every { userConfig.state } returns UserConfiguration.State()
            every { constructedWith<File>(EqMatcher(configPath)).parent } returns workingDir
            every { process.pid() } returns 2L
            every { process.onExit() } returns CompletableFuture.completedFuture(process)
            every { anyConstructed<StdErrListener>().listen() } returns Unit
            every { anyConstructed<GeneralCommandLine>().createProcess() } returns process

            editorProcess.initialize()

            assert(editorProcess.isAlive()) { "EditorProcess should be alive after initialization" }
        }

        test("isAlive returns false after destroy") {
            val editorProcess = EditorProcess(project)
            val execPath = "/bin/dprint"
            val configPath = "./dprint.json"
            val workingDir = "/working/dir"

            mockkConstructor(GeneralCommandLine::class)
            mockkConstructor(File::class)
            mockkConstructor(StdErrListener::class)

            every { getValidExecutablePath(project) } returns execPath
            every { getValidConfigPath(project) } returns configPath
            every { ProcessHandle.current() } returns processHandle
            every { processHandle.pid() } returns 1L
            every { userConfig.state } returns UserConfiguration.State()
            every { constructedWith<File>(EqMatcher(configPath)).parent } returns workingDir
            every { process.pid() } returns 2L
            every { process.onExit() } returns CompletableFuture.completedFuture(process)
            every { process.destroy() } returns Unit
            every { anyConstructed<StdErrListener>().listen() } returns Unit
            every { anyConstructed<StdErrListener>().dispose() } returns Unit
            every { anyConstructed<GeneralCommandLine>().createProcess() } returns process

            editorProcess.initialize()
            editorProcess.destroy()

            assert(!editorProcess.isAlive()) { "EditorProcess should not be alive after destroy" }
        }

        test("destroy is idempotent - multiple calls only destroy once") {
            val editorProcess = EditorProcess(project)
            val execPath = "/bin/dprint"
            val configPath = "./dprint.json"
            val workingDir = "/working/dir"

            mockkConstructor(GeneralCommandLine::class)
            mockkConstructor(File::class)
            mockkConstructor(StdErrListener::class)

            every { getValidExecutablePath(project) } returns execPath
            every { getValidConfigPath(project) } returns configPath
            every { ProcessHandle.current() } returns processHandle
            every { processHandle.pid() } returns 1L
            every { userConfig.state } returns UserConfiguration.State()
            every { constructedWith<File>(EqMatcher(configPath)).parent } returns workingDir
            every { process.pid() } returns 2L
            every { process.onExit() } returns CompletableFuture.completedFuture(process)
            every { process.destroy() } returns Unit
            every { anyConstructed<StdErrListener>().listen() } returns Unit
            every { anyConstructed<StdErrListener>().dispose() } returns Unit
            every { anyConstructed<GeneralCommandLine>().createProcess() } returns process

            editorProcess.initialize()

            // Call destroy multiple times
            editorProcess.destroy()
            editorProcess.destroy()
            editorProcess.destroy()

            // Verify process.destroy() was only called once
            verify(exactly = 1) { process.destroy() }
            verify(exactly = 1) { anyConstructed<StdErrListener>().dispose() }
        }

        test("initialize resets destroyed state allowing reuse") {
            val editorProcess = EditorProcess(project)
            val execPath = "/bin/dprint"
            val configPath = "./dprint.json"
            val workingDir = "/working/dir"

            mockkConstructor(GeneralCommandLine::class)
            mockkConstructor(File::class)
            mockkConstructor(StdErrListener::class)

            every { getValidExecutablePath(project) } returns execPath
            every { getValidConfigPath(project) } returns configPath
            every { ProcessHandle.current() } returns processHandle
            every { processHandle.pid() } returns 1L
            every { userConfig.state } returns UserConfiguration.State()
            every { constructedWith<File>(EqMatcher(configPath)).parent } returns workingDir
            every { process.pid() } returns 2L
            every { process.onExit() } returns CompletableFuture.completedFuture(process)
            every { process.destroy() } returns Unit
            every { anyConstructed<StdErrListener>().listen() } returns Unit
            every { anyConstructed<StdErrListener>().dispose() } returns Unit
            every { anyConstructed<GeneralCommandLine>().createProcess() } returns process

            // Initialize, destroy, then reinitialize
            editorProcess.initialize()
            assert(editorProcess.isAlive()) { "Should be alive after first initialization" }

            editorProcess.destroy()
            assert(!editorProcess.isAlive()) { "Should not be alive after destroy" }

            editorProcess.initialize()
            assert(editorProcess.isAlive()) { "Should be alive again after reinitialization" }
        }
    })
