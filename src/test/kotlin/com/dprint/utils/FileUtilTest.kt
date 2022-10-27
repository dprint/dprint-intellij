package com.dprint.utils

import com.intellij.testFramework.TestDataPath
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.io.path.Path
import kotlin.io.path.pathString

@TestDataPath("\$CONTENT_ROOT/src/test/data")
class FileUtilTest : BasePlatformTestCase() {

    override fun getTestDataPath() = "src/test/data/fileUtilsTest"

    fun testItValidatesAValidConfigFile() {
        val path = Path(testDataPath, "dprint.json").pathString
        assertTrue(validateConfigFile(myFixture.project, path))
    }
}
