// Application-wide settings (Settings | Tools | Herdr Cockpit). Machine-level on purpose: a project can't
// pick the program we launch or the socket we talk to.
package io.github.tezmanian.herdrcockpit

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.rows
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
    /** The prompt library (Settings | Tools | Herdr Cockpit | Prompts). */
    var library by list<PromptEntry>()
    /** Whether `prompts` was copied into `library` (once). */
    var promptsMigrated by property(false)
    /** Before the library: one "category: text" per line. Only read to fill the library once. */
    var prompts by string(LEGACY_PROMPTS)
    /** Whether the library's untouched first presets were replaced by DEFAULT_PROMPTS (once). */
    var presetsUpgraded by property(false)
    /** Whether DEFAULT_TEMPLATES were added to the library (once; apart from presetsUpgraded, which an early 0.1.23 set without them). */
    var templatesAdded by property(false)
    /** Review servers (GitLab, GitHub, Gitea) and how to sign in; tokens are in the password safe. */
    var servers by list<ServerEntry>()
    /** Before 0.1.21: global paths to the forge CLIs, now per server. Only read to move them there once. */
    var glabPath by string("")
    var ghPath by string("")
    var teaPath by string("")
    /** Kiro's monthly credit allowance (Kiro doesn't store it locally); 0 = unknown, no bar. */
    var kiroMonthlyCredits by property(0)
}

@Service(Service.Level.APP)
@State(name = "HerdrCockpitSettings", storages = [Storage("herdr-cockpit.xml")])
class HerdrSettings : SimplePersistentStateComponent<HerdrSettingsState>(HerdrSettingsState()) {
    companion object {
        fun get(): HerdrSettingsState = service<HerdrSettings>().state

        /** Your prompts; the first call turns the old one-per-line setting into entries. */
        fun prompts(): List<SavedPrompt> {
            val s = get()
            if (!s.promptsMigrated) {
                s.library = promptsFromLines(s.prompts).toEntries()
                s.promptsMigrated = true
            }
            if (!s.presetsUpgraded) {
                s.library = upgradePresets(s.library.toPrompts()).toEntries()
                s.presetsUpgraded = true
            }
            if (!s.templatesAdded) {
                s.library = (s.library.toPrompts() + DEFAULT_TEMPLATES).toEntries()
                s.templatesAdded = true
            }
            return s.library.toPrompts()
        }

        fun setPrompts(prompts: List<SavedPrompt>) {
            get().library = prompts.toEntries()
        }

        /** Your servers; the first call moves the old global CLI paths into them. */
        fun servers(): List<ReviewServer> {
            val s = get()
            val old = mapOf("glab" to s.glabPath, "gh" to s.ghPath, "tea" to s.teaPath).mapValues { it.value ?: "" }
            if (old.values.any { it.isNotBlank() }) {
                val (servers, left) = takeOverCliPaths(s.servers.mapNotNull { it.toServer(forges()) }, old)
                setServers(servers)
                s.glabPath = ""; s.ghPath = ""; s.teaPath = ""
                NotificationGroupManager.getInstance().getNotificationGroup("Herdr Cockpit").createNotification(
                    "The CLI paths are now set per server (Settings | Tools | Herdr Cockpit | Review servers)." +
                        if (left.isEmpty()) " Yours were moved there." else " Add a server to use " + left.entries.joinToString { "${it.key} at ${it.value}" } + ".",
                    NotificationType.INFORMATION).notify(null)
            }
            return s.servers.mapNotNull { it.toServer(forges()) }
        }

        /** Entries of a forge no plugin registers now (one turned off, say) stay as they are. */
        fun setServers(servers: List<ReviewServer>) {
            val s = get()
            val forges = forges()
            val unknown = s.servers.filter { e -> forges.none { it.title.equals(e.forge, ignoreCase = true) } }
            s.servers = (servers.map { it.toEntry() } + unknown).toMutableList()
        }

        /** Your entry for the server at `host`, if any. */
        fun serverFor(host: String) = servers().firstOrNull { it.host == host.lowercase() }
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
            group("Review servers") {
                val servers = serverTable()
                row {
                    cell(servers.component).align(Align.FILL)
                        .comment("Where \"Send to … as Drafts / Pending Review\" goes and how it signs in. A server without an entry is detected (gitlab.com, github.com, a CLI signed in there (found in the usual folders or on PATH), else the server itself) and signed in to automatically.")
                }.resizableRow()
                onReset { servers.items = HerdrSettings.servers() }
                onIsModified { servers.items != HerdrSettings.servers() }
                onApply {
                    // A removed server's token goes with it.
                    (HerdrSettings.servers().map { it.url } - servers.items.map { it.url }.toSet()).forEach { ReviewTokens.set(it, null) }
                    HerdrSettings.setServers(servers.items)
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
            group("Prompts") {
                val table = promptTable()
                row {
                    cell(table.component).align(Align.FILL)
                        .comment("Offered as presets in the prompt dialogs. $CATEGORY_HELP A project can add its own under Project Prompts.")
                }.resizableRow()
                onReset { table.items = HerdrSettings.prompts() }
                onIsModified { table.items != HerdrSettings.prompts() }
                onApply { HerdrSettings.setPrompts(table.items) }
            }
            group("Agent integrations") {
                val integrations = IntegrationTable()
                row {
                    cell(integrations.component).align(Align.FILL)
                        .comment("Small hooks that report an agent's session to Herdr, next to Herdr's own screen detection of its state. The agent's config folder must exist (run the agent once first). Install and Uninstall change that agent's own configuration (e.g. ~/.claude/settings.json).")
                }.resizableRow()
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
