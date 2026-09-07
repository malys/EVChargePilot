package com.evsuite.chargepilot

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import java.io.File

/**
 * The file and folder picker, built from plain alert dialogs — one dialog per directory, re-shown
 * at every step.
 *
 * This head unit has no document picker to delegate to: the system one answers "no apps can
 * perform this action". EVTasker's browser is the same chain of dialogs and has worked on this
 * car since it shipped, so this is that solution rather than a new one. A chain of dialogs needs
 * no layout, no fragment and no back stack, and its rows are dialog list items — the size a
 * driver hits with one finger.
 *
 * [roots] is passed in, never discovered here: scanning mount points can block on a slow stick,
 * and the callers already do it off the main thread. Listing one directory as the user walks
 * into it is a single `listFiles()` and stays where the dialog is.
 */
class StorageBrowserDialog private constructor(
    private val context: Context,
    private val roots: List<File>,
    /** True to answer with the directory the user stopped in, false to answer with a file. */
    private val folderMode: Boolean,
    @StringRes private val titleRes: Int,
    private val onPicked: (File) -> Unit,
) {

    companion object {
        /** Browse to an existing file. [roots] must not be empty. */
        fun pickFile(
            context: Context,
            roots: List<File>,
            @StringRes titleRes: Int,
            onPicked: (File) -> Unit,
        ) = StorageBrowserDialog(context, roots, folderMode = false, titleRes, onPicked).start()

        /** Browse to a directory to write into. [roots] must not be empty. */
        fun pickFolder(
            context: Context,
            roots: List<File>,
            @StringRes titleRes: Int,
            onPicked: (File) -> Unit,
        ) = StorageBrowserDialog(context, roots, folderMode = true, titleRes, onPicked).start()
    }

    private fun start() {
        // A single volume makes the chooser a list of one: open it instead of asking.
        if (roots.size == 1) showDirectory(roots[0]) else showRoots()
    }

    private fun showRoots() {
        AlertDialog.Builder(context)
            .setTitle(titleRes)
            .setItems(roots.map(File::getAbsolutePath).toTypedArray()) { _, which ->
                showDirectory(roots[which])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showDirectory(directory: File) {
        val entries = DiagnosticUsbStorage.children(directory)
        val labels = mutableListOf(context.getString(R.string.browser_up))
        // A trailing separator is the only affordance a plain list row has for "this opens".
        labels += entries.map { if (it.isDirectory) "${it.name}/" else it.name }

        val builder = AlertDialog.Builder(context)
            .setTitle(directory.absolutePath)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) {
                    goUp(directory)
                } else {
                    val entry = entries[which - 1]
                    if (entry.isDirectory) showDirectory(entry) else onPicked(entry)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)

        // Folder mode has no file to tap: the directory the user stopped in is the answer.
        if (folderMode) {
            builder.setPositiveButton(R.string.browser_use_folder) { _, _ -> onPicked(directory) }
        }
        builder.show()
    }

    /** Up from a volume root returns to the volume list, never to `/storage` itself. */
    private fun goUp(directory: File) {
        if (roots.none { it == directory }) {
            showDirectory(directory.parentFile ?: return)
        } else if (roots.size > 1) {
            showRoots()
        }
        // Single root, already at the top: the dialog has closed, which is the way out.
    }
}
