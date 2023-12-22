package com.dprint.formatter

import com.intellij.formatting.service.DocumentMerger
import com.intellij.openapi.editor.Document

class DprintDocumentMerger : DocumentMerger {
    override fun updateDocument(
        document: Document,
        newText: String,
    ): Boolean {
        if (document.isWritable) {
            document.setText(newText)
            return true
        }

        return false
    }
}
