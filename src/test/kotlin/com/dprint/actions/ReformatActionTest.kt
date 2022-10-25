package com.dprint.actions

import com.dprint.config.ProjectConfiguration
import com.intellij.lang.javascript.linter.JSExternalToolIntegrationTest
import com.intellij.openapi.components.service

class ReformatActionTest : JSExternalToolIntegrationTest() {
    override fun getMainPackageName(): String {
        return "dprint"
    }

    override fun setUp() {
        super.setUp()
        myFixture.testDataPath = "src/test/data/" + getTestName(true)
        project.service<ProjectConfiguration>().state.enabled = true
        myFixture.copyFileToProject("dprint.json")
        myFixture.copyDirectoryToProject(getTestName(true), "")
    }

    fun testItFormats() {
        myFixture.configureByFile("test.ts")
        myFixture.testAction(ReformatAction())
        myFixture.checkResultByFile("result.ts")
    }
}
