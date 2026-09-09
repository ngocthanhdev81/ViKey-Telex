package dev.ngocthanhgl.vikey.app.settings.about

import android.content.Context
import android.net.Uri
import dev.ngocthanhgl.vikey.BuildConfig
import dev.ngocthanhgl.vikey.editorInstance

/**
 * TEMP DEBUG (delete this file and the AboutScreen row together with the
 * export-log commit once the Telex-death hunt is over): composes the
 * keystroke-process log at export time from the editor breadcrumb ring.
 * No new permissions, no manifest changes, no Flog changes.
 */
fun buildTempDebugLog(context: Context): String {
    val crumbs = try {
        context.editorInstance().value.getCommitBreadcrumbs()
    } catch (e: Throwable) {
        return "vikey temp debug log\nversion: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "ERROR reading breadcrumbs: $e\n"
    }
    val noMatch = try {
        context.editorInstance().value.noMatchSelectionUpdates
    } catch (_: Throwable) {
        -1
    }
    val evictions = try {
        context.editorInstance().value.queueCapEvictions
    } catch (_: Throwable) {
        -1
    }
    return buildString {
        appendLine("vikey temp debug log")
        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("noMatchSelectionUpdates(consecutive): $noMatch")
        appendLine("queueCapEvictions: $evictions")
        appendLine("breadcrumb entries: ${crumbs.size}")
        appendLine("---")
        if (crumbs.isEmpty()) {
            appendLine("(empty: IME process was restarted or no keystrokes since start)")
        } else {
            crumbs.forEach { appendLine(it) }
        }
    }
}

fun writeTempDebugLog(context: Context, uri: Uri) {
    context.contentResolver.openOutputStream(uri)?.use { out ->
        out.write(buildTempDebugLog(context).toByteArray())
    } ?: error("cannot open output stream for $uri")
}
