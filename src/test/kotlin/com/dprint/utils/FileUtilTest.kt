package com.dprint.utils

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FileUtilTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/data/fileUtilsTest"

    fun testItValidatesAValidConfigFile() {
        val path = "$testDataPath/dprint.json"
        assertTrue(validateConfigFile(myFixture.project, path))
    }
}
