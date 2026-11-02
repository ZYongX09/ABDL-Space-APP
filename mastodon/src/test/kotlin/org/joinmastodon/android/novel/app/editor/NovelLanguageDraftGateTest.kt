package org.joinmastodon.android.novel.app.editor

import org.junit.Assert.*
import org.junit.Test

/** Synchronous language gating must never report a pending save/form/import as safe. */
class NovelLanguageDraftGateTest {
    @Test fun busySavingDirtyAndUncommittedImportVetoRecreation() {
        val clean = NovelEditorState(page = "manage", workId = "saved-local")
        assertTrue(clean.canChangeAppLanguage())
        assertFalse(clean.copy(busy = true).canChangeAppLanguage())
        assertFalse(clean.copy(saving = true).canChangeAppLanguage())
        assertFalse(clean.copy(dirty = true).canChangeAppLanguage())
        assertFalse(clean.copy(page = "importPreview").canChangeAppLanguage())
        assertFalse(clean.copy(page = "adjust").canChangeAppLanguage())
        assertFalse(NovelEditorState().canChangeAppLanguage())
    }

    @Test fun anUnstoredCreateFormVetoesEvenWithoutManuscriptDirtyFlag() {
        val empty = NovelEditorState(page = "metadata")
        assertTrue(empty.canChangeAppLanguage())
        assertFalse(empty.copy(metadata = NovelMetadata(title = "Unsaved title")).canChangeAppLanguage())
        assertFalse(empty.copy(metadata = NovelMetadata(description = "Unsaved description")).canChangeAppLanguage())
        assertFalse(empty.copy(metadata = NovelMetadata(type = "repost")).canChangeAppLanguage())
        assertFalse(empty.copy(metadata = NovelMetadata(accepted = true)).canChangeAppLanguage())
        assertFalse(empty.copy(metadata = NovelMetadata(localCover = "/private/selected.png")).canChangeAppLanguage())
    }
}
