// Application-wide settings (Settings | Tools | Herdr Cockpit). Machine-level on purpose: a project can't
// pick the program we launch or the socket we talk to.
package io.github.tezmanian.herdrcockpit

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.panel

class HerdrSettingsState : BaseState() {
    /** Path to the herdr binary; empty = look in the usual install folders, then PATH. */
    var binaryPath by string("")
    /** Herdr socket; empty = HERDR_SOCKET_PATH, HERDR_SESSION or ~/.config/herdr/herdr.sock. */
    var socketPath by string("")
    /** When Herdr's focused space changes, switch here too (only in projects you switched from the IDE). */
    var followMode by property(true)
    /** Switching a space opens a tab for every pane. */
    var autoAttachOnSwitch by property(true)
    /** ...including shell panes, not only agents. */
    var autoAttachShells by property(true)
    /** Switching a space closes (detaches) the tabs of other spaces. */
    var closeTerminalsOnSwitch by property(true)
    /** Switching a space adds its folder as a content root of this project. */
    var mountOnSwitch by property(true)
    /** Sidebar order: "activity" (busy and recently active spaces first) or "herdr" (Herdr's order). */
    var spaceOrder by string("activity")
    var showShells by property(true)
    var notifyBlocked by property(true)
    var notifyDone by property(true)
    /** Say when another client (VS Code, another window, `herdr attach`) took over panes this window shows. */
    var notifyOnTakeover by property(true)
    /** Agents offered first for a new tab (any kind `herdr agent start --kind` accepts). */
    var agents by string("claude, codex, kiro, cursor")
    /** Closing a pane's editor tab yourself: "never" only detaches, "ask" closes idle shells in Herdr and asks for the rest, "always" closes it. */
    var closeTabInHerdr by string("never")
    /** Starting Herdr or opening the TUI creates a space for every project of the window (attached ones too) that has none. */
    var spacePerProject by property(false)
    /** Kiro's monthly credit allowance (Kiro doesn't store it locally); 0 = unknown, no bar. */
    var kiroMonthlyCredits by property(0)
}

@Service(Service.Level.APP)
@State(name = "HerdrCockpitSettings", storages = [Storage("herdr-cockpit.xml")])
class HerdrSettings : SimplePersistentStateComponent<HerdrSettingsState>(HerdrSettingsState()) {
    companion object {
        fun get(): HerdrSettingsState = service<HerdrSettings>().state
    }
}

class HerdrConfigurable : BoundConfigurable("Herdr Cockpit") {
    override fun createPanel(): DialogPanel {
        val s = HerdrSettings.get()
        return panel {
            group("Connection") {
                row("herdr binary:") {
                    textField().bindText({ s.binaryPath ?: "" }, { s.binaryPath = it }).columns(COLUMNS_LARGE)
                        .comment("Empty: ~/.local/bin, Homebrew, /usr/bin and the other usual folders, then PATH.")
                }
                row("Socket:") {
                    textField().bindText({ s.socketPath ?: "" }, { s.socketPath = it }).columns(COLUMNS_LARGE)
                        .comment("Empty: HERDR_SOCKET_PATH, HERDR_SESSION, else ~/.config/herdr/herdr.sock. A …/sessions/&lt;name&gt;/herdr.sock path selects that session.")
                }
            }
            group("Starting Herdr") {
                row { checkBox("Create a space for every project in the window").bindSelected(s::spacePerProject)
                    .comment("When you start Herdr or open the TUI: the project and the ones attached to it, each in its folder, if it has no space yet. Spaces are never closed for you.") }
            }
            group("Switching spaces") {
                row { checkBox("Add the space's folder as a content root").bindSelected(s::mountOnSwitch) }
                row { checkBox("Open a tab for every pane").bindSelected(s::autoAttachOnSwitch) }
                row { checkBox("Include shell panes").bindSelected(s::autoAttachShells) }
                row { checkBox("Close the tabs of other spaces (they only detach)").bindSelected(s::closeTerminalsOnSwitch) }
                row { checkBox("Follow Herdr's focus (in projects you switched from the IDE)").bindSelected(s::followMode) }
            }
            group("Sidebar and notifications") {
                row("Order of spaces:") {
                    comboBox(listOf("activity", "herdr")).bindItem({ s.spaceOrder ?: "activity" }, { s.spaceOrder = it ?: "activity" })
                        .comment("activity: spaces with an agent working or waiting on you first, then the most recently active (an agent changed state, or you switched to it). herdr: Herdr's order.")
                }
                row { checkBox("Show shell panes").bindSelected(s::showShells) }
                row { checkBox("Notify when an agent is blocked").bindSelected(s::notifyBlocked) }
                row { checkBox("Notify when an agent is done").bindSelected(s::notifyDone) }
                row { checkBox("Notify when another client takes a pane over").bindSelected(s::notifyOnTakeover) }
            }
            group("Usage") {
                row("Kiro monthly credits:") {
                    intTextField(0..10_000_000).bindIntText(s::kiroMonthlyCredits)
                        .comment("Your plan's allowance (Kiro shows it in /usage). 0 hides the bar.")
                }
            }
            group("Closing tabs") {
                row("Closing a pane's tab yourself:") {
                    comboBox(listOf("never", "ask", "always")).bindItem({ s.closeTabInHerdr ?: "never" }, { s.closeTabInHerdr = it ?: "never" })
                        .comment("never: only detaches, the pane keeps running in Herdr. ask: closes idle shells in Herdr too, asks for agents and busy shells. always: closes it in Herdr. The last pane of a space always asks.")
                }
            }
            group("New tabs") {
                row("Agents:") {
                    textField().bindText({ s.agents ?: "" }, { s.agents = it }).columns(COLUMNS_LARGE)
                        .comment("Listed first when you create a tab, comma-separated: ${AGENT_KINDS.keys.joinToString(", ")}. The rest are under \"More agents…\".")
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        for (p in ProjectManager.getInstance().openProjects) HerdrService.getInstance(p).settingsChanged()
    }
}
