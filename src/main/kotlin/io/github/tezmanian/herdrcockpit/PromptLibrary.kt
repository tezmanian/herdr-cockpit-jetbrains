// The prompt library: saved prompts with a name, a category and a (multi-line) text, IDE-wide and per project. The
// prompt dialogs offer them as presets. Project prompts live in .idea/ (shareable through the repository) and go to
// agents, so they're only offered in trusted projects and marked "(project)".
package io.github.tezmanian.herdrcockpit

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class PromptEntry : BaseState() {
    var name by string("")
    var category by string("review")
    var text by string("")
}

private fun PromptEntry.toPrompt() = SavedPrompt(name ?: "", category ?: "general", text ?: "")
private fun SavedPrompt.toEntry() = PromptEntry().also { it.name = name; it.category = category; it.text = text }
fun List<PromptEntry>.toPrompts() = map { it.toPrompt() }
fun List<SavedPrompt>.toEntries() = mapTo(mutableListOf()) { it.toEntry() }

class ProjectPromptsState : BaseState() {
    var prompts by list<PromptEntry>()
}

/** The project's prompts, in .idea/herdr-cockpit-prompts.xml. */
@Service(Service.Level.PROJECT)
@State(name = "HerdrCockpitPrompts", storages = [Storage("herdr-cockpit-prompts.xml")])
class HerdrProjectPrompts : SimplePersistentStateComponent<ProjectPromptsState>(ProjectPromptsState()) {
    var prompts: List<SavedPrompt>
        get() = state.prompts.toPrompts()
        set(v) { state.prompts = v.toEntries() }
}

/** The presets for a dialog of `category`: (label, text), yours first, then the project's when it's trusted. */
fun promptPresets(project: Project, category: String): List<Pair<String, String>> {
    val own = HerdrSettings.prompts().filter { it.category == category }.map { it.name to it.text }
    if (!TrustedProjects.isProjectTrusted(project)) return own
    return own + project.service<HerdrProjectPrompts>().prompts.filter { it.category == category }.map { "${it.name} (project)" to it.text }
}

/** A table of prompts: Name | Category | Text. */
fun promptTable() = EntryTable<SavedPrompt>(listOf(
    "Name" to { it.name },
    "Category" to { it.category },
    "Text" to { it.text.lineSequence().first() + if (it.text.contains('\n')) " …" else "" },
), ::editPrompt)

private fun editPrompt(parent: JComponent, p: SavedPrompt?): SavedPrompt? {
    val name = JBTextField(p?.name ?: "")
    val category = ComboBox(PROMPT_CATEGORIES.toTypedArray()).apply { selectedItem = p?.category ?: "review" }
    val text = JBTextArea(p?.text ?: "", 12, 70).apply { lineWrap = true; wrapStyleWord = true }
    val content = panel {
        row("Name:") { cell(name).align(AlignX.FILL) }
        row("Category:") {
            cell(category).comment("review: Review Branch with Agent… and Review with Agent…; general: Send to Agent… and Hand Off to Agent….")
        }
        row { scrollCell(text).align(Align.FILL) }.resizableRow()
    }
    val dialog = object : DialogWrapper(parent, true) {
        init { title = if (p == null) "New Prompt" else "Edit Prompt"; init(); initValidation() }
        override fun createCenterPanel() = content
        override fun getPreferredFocusedComponent(): JComponent = if (p == null) name else text
        override fun doValidate() = when {
            name.text.isBlank() -> ValidationInfo("Give it a name", name)
            text.text.isBlank() -> ValidationInfo("Write the prompt", text)
            else -> null
        }
    }
    if (!dialog.showAndGet()) return null
    return SavedPrompt(name.text.trim(), category.selectedItem as String, text.text.trim())
}

/** Settings | Tools | Herdr Cockpit | Project Prompts. */
class HerdrProjectPromptsConfigurable(private val project: Project) : BoundConfigurable("Project Prompts") {
    override fun createPanel(): DialogPanel {
        val store = project.service<HerdrProjectPrompts>()
        val table = promptTable()
        return panel {
            row {
                text("This project's prompts, offered next to yours (marked \"(project)\") in trusted projects. Stored in " +
                    ".idea/herdr-cockpit-prompts.xml: commit it to share them.")
            }
            row { cell(table.component).align(Align.FILL) }.resizableRow()
            onReset { table.items = store.prompts }
            onIsModified { table.items != store.prompts }
            onApply { store.prompts = table.items }
        }
    }
}
