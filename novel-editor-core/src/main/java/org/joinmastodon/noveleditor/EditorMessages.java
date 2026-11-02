package org.joinmastodon.noveleditor;

import android.content.Context;

/** Display-only mapping; diagnostic exceptions, record constructors, and canonical import text stay unchanged. */
public final class EditorMessages {
    private EditorMessages() {}
    public static String forCode(Context context, String code) {
        int resource = switch(code) {
            case "CANCELED" -> R.string.novel_editor_canceled;
            case "SOURCE_EMPTY" -> R.string.novel_editor_source_empty;
            case "SOURCE_TOO_LARGE" -> R.string.novel_editor_source_too_large;
            case "UNSUPPORTED_FORMAT" -> R.string.novel_editor_unsupported_format;
            case "URI_PERMISSION_DENIED" -> R.string.novel_editor_uri_permission_denied;
            case "STORAGE_FULL" -> R.string.novel_editor_storage_full;
            case "CORRUPT_ZIP" -> R.string.novel_editor_corrupt_zip;
            case "ZIP_ENTRY_LIMIT" -> R.string.novel_editor_zip_entry_limit;
            case "ARCHIVE_PATH_INVALID" -> R.string.novel_editor_archive_path_invalid;
            case "PACKAGE_MISSING" -> R.string.novel_editor_package_missing;
            case "ZIP_EXPANDED_SIZE_LIMIT" -> R.string.novel_editor_zip_expanded_size_limit;
            case "ZIP_RATIO_LIMIT" -> R.string.novel_editor_zip_ratio_limit;
            case "DRM_EPUB" -> R.string.novel_editor_drm_epub;
            case "TEXT_LIMIT_EXCEEDED" -> R.string.novel_editor_text_limit_exceeded;
            case "NO_READABLE_TEXT" -> R.string.novel_editor_no_readable_text;
            case "TEXT_DECODE_FAILED" -> R.string.novel_editor_text_decode_failed;
            case "XML_INVALID" -> R.string.novel_editor_xml_invalid;
            case "TOO_MANY_CHAPTERS" -> R.string.novel_editor_too_many_chapters;
            case "ENCODING_AMBIGUOUS" -> R.string.novel_editor_encoding_ambiguous;
            case "EPUB_ENCRYPTION_PRESENT" -> R.string.novel_editor_epub_encryption_present;
            case "EPUB_IMAGES_SKIPPED" -> R.string.novel_editor_epub_images_skipped;
            case "DOCX_IMAGES_SKIPPED" -> R.string.novel_editor_docx_images_skipped;
            case "DOCX_NOTES_SKIPPED" -> R.string.novel_editor_docx_notes_skipped;
            default -> R.string.novel_editor_import_failed;
        };
        return context.getString(resource);
    }
    public static String localize(Context context, String message) {
        int resource = switch(message) {
            case "Empty import" -> R.string.novel_editor_validation_0;
            case "Invalid position" -> R.string.novel_editor_validation_1;
            case "Select adjacent chapters" -> R.string.novel_editor_validation_2;
            case "Merged chapter exceeds editor limit" -> R.string.novel_editor_validation_3;
            case "Select a paragraph boundary" -> R.string.novel_editor_validation_4;
            case "Chapter too long" -> R.string.novel_editor_validation_5;
            case "Text contains NUL" -> R.string.novel_editor_validation_6;
            case "Title length out of range" -> R.string.novel_editor_validation_7;
            case "Invalid metadata" -> R.string.novel_editor_validation_8;
            case "Metadata not found" -> R.string.novel_editor_validation_9;
            case "Chapter not found" -> R.string.novel_editor_validation_10;
            case "Work not found" -> R.string.novel_editor_validation_11;
            case "Invalid description" -> R.string.novel_editor_validation_12;
            case "Chapter belongs to another work" -> R.string.novel_editor_validation_13;
            case "Chapter list changed" -> R.string.novel_editor_validation_14;
            case "Select 1–1000 unique chapters" -> R.string.novel_editor_validation_15;
            case "Load selected chapters first" -> R.string.novel_editor_validation_16;
            case "Selected chapter is empty" -> R.string.novel_editor_validation_17;
            case "Selected text exceeds 20 MiB" -> R.string.novel_editor_validation_18;
            case "Chapter order conflicts; refresh the owner directory" -> R.string.novel_editor_validation_19;
            case "Submission not found" -> R.string.novel_editor_validation_20;
            case "Import is not ready" -> R.string.novel_editor_validation_21;
            case "Select chapters" -> R.string.novel_editor_validation_22;
            case "No chapters selected" -> R.string.novel_editor_validation_23;
            case "Import changed" -> R.string.novel_editor_validation_24;
            case "Import job changed" -> R.string.novel_editor_validation_25;
            case "Source size out of range" -> R.string.novel_editor_validation_26;
            case "Invalid import result" -> R.string.novel_editor_validation_27;
            case "Import text too large" -> R.string.novel_editor_validation_28;
            case "Import job not found" -> R.string.novel_editor_validation_29;
            case "Description too long" -> R.string.novel_editor_validation_30;
            case "Chapter content too long" -> R.string.novel_editor_validation_31;
            case "Chapter changed concurrently" -> R.string.novel_editor_validation_32;
            case "Draft not found" -> R.string.novel_editor_validation_33;
            case "Owner client_work_id required" -> R.string.novel_editor_validation_34;
            case "Owner client_work_id changed" -> R.string.novel_editor_validation_35;
            case "Owner state changed; refresh before confirming" -> R.string.novel_editor_validation_36;
            case "Invalid edit operation" -> R.string.novel_editor_validation_37;
            case "Invalid edit" -> R.string.novel_editor_validation_38;
            case "Journal base generation changed" -> R.string.novel_editor_validation_39;
            case "Journal base generation mismatch" -> R.string.novel_editor_validation_40;
            case "Publish snapshot changed" -> R.string.novel_editor_validation_41;
            case "Publish operation not found" -> R.string.novel_editor_validation_42;
            case "Trashed work not found" -> R.string.novel_editor_validation_43;
            default -> 0;
        };
        return resource == 0 ? message : context.getString(resource);
    }
}
