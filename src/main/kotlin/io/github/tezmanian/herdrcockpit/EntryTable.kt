// A settings table of entries with add, remove, edit (also by double-click) and move. It edits a copy until the
// settings are applied. Used by the prompt library and the review servers.
package io.github.tezmanian.herdrcockpit

import com.intellij.ui.DoubleClickListener
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** `edit(parent, entry)` opens the entry's dialog (null: a new one) and returns the result, or null when cancelled. */
class EntryTable<T>(columns: List<Pair<String, (T) -> String>>, private val edit: (JComponent, T?) -> T?) {
    private val model = ListTableModel<T>(*columns.map { (title, value) ->
        object : ColumnInfo<T, String>(title) {
            override fun valueOf(item: T) = value(item)
        }
    }.toTypedArray())
    private val table = TableView<T>(model).apply { setShowGrid(false) }

    var items: List<T>
        get() = model.items.toList()
        set(v) { model.items = v.toMutableList() }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction { edit(table, null)?.let { model.addRow(it); table.selection = listOf(it) } }
        .setEditAction { editSelected() }
        .createPanel()

    init {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean { editSelected(); return true }
        }.installOn(table)
    }

    private fun editSelected() {
        val row = table.selectedRow.takeIf { it >= 0 } ?: return
        val i = table.convertRowIndexToModel(row)
        edit(table, model.getItem(i))?.let { model.setItem(i, it) }
    }
}
