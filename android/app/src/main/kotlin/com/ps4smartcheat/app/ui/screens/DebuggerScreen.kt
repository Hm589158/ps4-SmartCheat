package com.ps4smartcheat.app.ui.screens

import com.ps4smartcheat.app.ui.components.GameRequiredBanner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ps4smartcheat.app.data.AppContainer
import com.ps4smartcheat.app.ui.appViewModel
import com.ps4smartcheat.app.ui.components.ChoiceRow
import com.ps4smartcheat.app.ui.components.ConfirmDialog
import com.ps4smartcheat.app.ui.components.ErrorText
import com.ps4smartcheat.app.ui.components.LabeledSwitch
import com.ps4smartcheat.app.ui.components.SectionCard
import com.ps4smartcheat.app.ui.components.Selector
import com.ps4smartcheat.app.ui.components.StatusPill
import com.ps4smartcheat.app.ui.components.Tone
import com.ps4smartcheat.app.ui.components.WarningBanner
import com.ps4smartcheat.app.ui.components.copyText
import com.ps4smartcheat.core.backend.BackendException
import com.ps4smartcheat.core.backend.ConnectionState
import com.ps4smartcheat.core.cheat.ModuleResolver
import com.ps4smartcheat.core.debug.BreakpointHit
import com.ps4smartcheat.core.debug.DebugSession
import com.ps4smartcheat.core.debug.ExecutionDebugger
import com.ps4smartcheat.core.debug.InterruptListener
import com.ps4smartcheat.core.debug.RegisterFormat
import com.ps4smartcheat.core.debug.RegisterSnapshot
import com.ps4smartcheat.core.debug.Registers
import com.ps4smartcheat.core.debug.ReleaseReport
import com.ps4smartcheat.core.debug.TraceEnd
import com.ps4smartcheat.core.debug.TraceFormat
import com.ps4smartcheat.core.debug.TraceOptions
import com.ps4smartcheat.core.debug.TraceStep
import com.ps4smartcheat.core.diag.ErrorAdvisor
import com.ps4smartcheat.core.logging.EventCategory
import com.ps4smartcheat.core.util.Address
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What happens when the breakpoint is reached. */
enum class DebugMode(val title: String, val description: String) {
    BREAK_ONCE("Breakpoint", "Capture the registers and the call stack the first time this instruction runs. The game continues right away."),
    TRACE("Register trace", "From the breakpoint, record the next instructions with the registers each one changes. The game continues when the trace ends."),
    MANUAL("Break and step", "Pause the game at the breakpoint and step by hand. It is resumed automatically after a minute without input."),
}

enum class DebugPhase(val label: String, val tone: Tone) {
    IDLE("Ready", Tone.NEUTRAL),
    ATTACHING("Attaching…", Tone.WARN),
    WAITING("Waiting for the breakpoint", Tone.WARN),
    PAUSED("Game paused", Tone.BAD),
    TRACING("Tracing…", Tone.WARN),
    RELEASING("Releasing…", Tone.WARN),
    DONE("Finished", Tone.GOOD),
    FAILED("Failed", Tone.BAD),
}

private enum class StepCommand { STEP, STEP_OVER, TRACE, RESUME }

/**
 * Execution breakpoint, single-step and register trace with ps4debug-NG's debugger. Nothing is written to game
 * memory (the breakpoint is a debug register). The game is paused from the hit until it is resumed; every way out of a
 * session — finished, stopped, failed, screen closed — goes through the verified release (resume, clear, detach).
 */
class DebuggerViewModel(private val c: AppContainer) : ViewModel() {
    val selected = c.selectedProcess
    val connection = c.backend.state

    private val _address = MutableStateFlow("")
    val address = _address.asStateFlow()
    private val _mode = MutableStateFlow(DebugMode.BREAK_ONCE)
    val mode = _mode.asStateFlow()
    private val _traceSteps = MutableStateFlow(32)
    val traceSteps = _traceSteps.asStateFlow()
    private val _stepOver = MutableStateFlow(true)
    val stepOver = _stepOver.asStateFlow()
    private val _stayInFunction = MutableStateFlow(true)
    val stayInFunction = _stayInFunction.asStateFlow()
    private val _phase = MutableStateFlow(DebugPhase.IDLE)
    val phase = _phase.asStateFlow()
    private val _status = MutableStateFlow<String?>(null)
    val status = _status.asStateFlow()
    private val _hit = MutableStateFlow<BreakpointHit?>(null)
    val hit = _hit.asStateFlow()
    private val _steps = MutableStateFlow<List<TraceStep>>(emptyList())
    val steps = _steps.asStateFlow()
    private val _release = MutableStateFlow<ReleaseReport?>(null)
    val release = _release.asStateFlow()
    private val _idleLeft = MutableStateFlow(0)
    /** Seconds until a paused game is resumed automatically. */
    val idleLeft = _idleLeft.asStateFlow()
    private val _busy = MutableStateFlow(false)
    /** A step or trace requested while paused is running. */
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var job: Job? = null
    private var commands = Channel<StepCommand>(Channel.UNLIMITED)
    @Volatile private var stopRequested = false
    private var initialized = false

    val running: Boolean get() = _phase.value !in setOf(DebugPhase.IDLE, DebugPhase.DONE, DebugPhase.FAILED)

    fun init(address: Long?) {
        if (initialized) return
        initialized = true
        address?.let { _address.value = Address.format(it) }
    }

    fun setAddress(v: String) { _address.value = v.trim(); _error.value = null }
    fun setMode(v: DebugMode) { _mode.value = v }
    fun setTraceSteps(v: Int) { _traceSteps.value = v }
    fun setStepOver(v: Boolean) { _stepOver.value = v }
    fun setStayInFunction(v: Boolean) { _stayInFunction.value = v }

    fun relative(a: Long): String = c.selectedProcess.value?.regions?.let { ModuleResolver.refFor(a, it) }
        ?.let { "${it.name}+0x${java.lang.Long.toHexString(it.offset).uppercase()}" } ?: ""

    private fun options() = TraceOptions(maxSteps = _traceSteps.value, stepOverCalls = _stepOver.value, stayInFunction = _stayInFunction.value)

    fun start() {
        val sel = c.selectedProcess.value ?: run { _error.value = "Select the game process first"; return }
        val addr = Address.parse(_address.value) ?: run { _error.value = "Enter the hexadecimal address of an instruction"; return }
        if (running) return
        val mode = _mode.value
        _hit.value = null; _steps.value = emptyList(); _error.value = null; _release.value = null; _message.value = null
        stopRequested = false
        commands = Channel(Channel.UNLIMITED)
        _phase.value = DebugPhase.ATTACHING
        _status.value = "Attaching the debugger to ${sel.name}…"
        job = viewModelScope.launch {
            var session: DebugSession? = null
            try {
                val listener = withContext(Dispatchers.IO) { InterruptListener.open() }
                val attached = DebugSession.attach(c.transport, sel.pid, listener, events = c.log)
                session = attached
                val dbg = ExecutionDebugger(c.backend, attached, events = c.log)
                _phase.value = DebugPhase.WAITING
                _status.value = "Waiting for the game to reach ${Address.format(addr)} — play until that code runs"
                val (h, end) = dbg.awaitHit(addr, HIT_TIMEOUT_MS, shouldStop = { stopRequested })
                if (h == null) {
                    _status.value = "No hit: ${end.label}."
                } else {
                    _hit.value = h
                    _steps.value = dbg.recorded
                    when (mode) {
                        DebugMode.BREAK_ONCE -> _status.value = "Captured at ${Address.format(addr)} (thread ${h.threadName})."
                        DebugMode.TRACE -> {
                            _phase.value = DebugPhase.TRACING
                            _status.value = "Recording up to ${_traceSteps.value} instructions…"
                            val r = dbg.trace(options(), shouldStop = { stopRequested }) { _steps.value = it }
                            _steps.value = r.steps
                            _status.value = "Trace: ${r.steps.size} instruction(s), ${r.reason}."
                        }
                        DebugMode.MANUAL -> manual(dbg)
                    }
                }
            } catch (e: CancellationException) {
                _status.value = "Stopped."
                throw e
            } catch (e: BackendException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            } catch (e: IllegalArgumentException) {
                _error.value = e.message
            } catch (e: java.io.IOException) {
                _error.value = ErrorAdvisor.diagnose(e).format()
            } finally {
                // Always: resume the game, clear the debug registers, detach — verified (runs even when cancelled).
                val s = session
                if (s != null) {
                    _phase.value = DebugPhase.RELEASING
                    val report = s.release()
                    _release.value = report
                    _phase.value = if (report.confirmed && _error.value == null) DebugPhase.DONE else DebugPhase.FAILED
                } else {
                    _phase.value = DebugPhase.FAILED
                }
                _idleLeft.value = 0
                _busy.value = false
                c.log.info("debugger session ended", EventCategory.PROCESS)
            }
        }
    }

    /** Paused at the breakpoint: run the user's commands until Resume, an error, or [IDLE_SECONDS] without input. */
    private suspend fun manual(dbg: ExecutionDebugger) {
        _phase.value = DebugPhase.PAUSED
        _status.value = "Paused after the breakpoint instruction. Step, trace or resume."
        var left = IDLE_SECONDS
        _idleLeft.value = left
        while (dbg.paused && !stopRequested) {
            val cmd = withTimeoutOrNull(1000) { commands.receive() }
            if (cmd == null) {
                left--
                _idleLeft.value = left
                if (left <= 0) { _status.value = "Resumed automatically after $IDLE_SECONDS s without input."; return }
                continue
            }
            if (cmd == StepCommand.RESUME) { _status.value = "Resumed."; return }
            _busy.value = true
            val end: TraceEnd = when (cmd) {
                StepCommand.STEP -> dbg.step().second
                StepCommand.STEP_OVER -> dbg.stepOver().second
                else -> { _phase.value = DebugPhase.TRACING; dbg.trace(options(), shouldStop = { stopRequested }) { _steps.value = it }.end }
            }
            _steps.value = dbg.recorded
            _busy.value = false
            left = IDLE_SECONDS
            _idleLeft.value = left
            if (!dbg.paused) { _status.value = "Stepping ended: ${end.label}."; return }
            _phase.value = DebugPhase.PAUSED
            _status.value = when (end) {
                TraceEnd.COMPLETED -> "Paused at ${Address.format(dbg.recorded.last().address)}."
                else -> "Paused at ${Address.format(dbg.recorded.last().address)} — ${end.label}."
            }
        }
    }

    fun step() { commands.trySend(StepCommand.STEP) }
    fun stepOverOnce() { commands.trySend(StepCommand.STEP_OVER) }
    fun traceMore() { commands.trySend(StepCommand.TRACE) }
    fun resume() { commands.trySend(StepCommand.RESUME) }

    /** Ends the session normally: the wait or trace stops, the game is resumed and the session released. */
    fun stop() {
        stopRequested = true
        commands.trySend(StepCommand.RESUME)
    }

    /** Opens a recorded step in the disassembler with its registers (and the hit's call stack). */
    fun open(s: TraceStep): Long {
        c.registerContext.value = RegisterSnapshot(
            s.registers.values, s.address, s.threadName, System.currentTimeMillis(), fromSaved = false,
            callStack = _hit.value?.callStack.orEmpty(),
        )
        return s.address
    }

    fun traceText(): String? {
        val list = _steps.value
        if (list.isEmpty()) return null
        _message.value = "Copied ${list.size} step(s)"
        return TraceFormat.format(list, ::relative)
    }

    override fun onCleared() {
        // Leaving the screen ends the session; the coroutine's cleanup (release) still runs to completion.
        stopRequested = true
        commands.trySend(StepCommand.RESUME)
        super.onCleared()
    }

    companion object {
        val STEP_COUNTS = listOf(8, 16, 32, 64, 128, 256)
        const val HIT_TIMEOUT_MS = 120_000L
        const val IDLE_SECONDS = 60
    }
}

@Composable
fun DebuggerScreen(initialAddress: Long?, onOpenCode: (Long) -> Unit) {
    val vm = appViewModel { DebuggerViewModel(it) }
    LaunchedEffect(Unit) { vm.init(initialAddress) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val traceSteps by vm.traceSteps.collectAsStateWithLifecycle()
    val stepOver by vm.stepOver.collectAsStateWithLifecycle()
    val stayInFunction by vm.stayInFunction.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val hit by vm.hit.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val release by vm.release.collectAsStateWithLifecycle()
    val idleLeft by vm.idleLeft.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val info = (connection as? ConnectionState.Connected)?.info
    val ng = info?.capabilities?.isNg == true
    val portOk = info?.capabilities?.attachPort == true
    val running = phase !in setOf(DebugPhase.IDLE, DebugPhase.DONE, DebugPhase.FAILED)
    var confirm by remember { mutableStateOf(false) }
    var allRegs by rememberSaveable { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

    LazyColumn(Modifier.fillMaxSize().testTag("debugList"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GameRequiredBanner()
                if (info != null && !ng) WarningBanner("Breakpoints and stepping need ps4debug-NG.")
                if (ng && !portOk) WarningBanner(
                    "This ps4debug-NG reports capability level ${info?.capabilities?.capabilityLevel ?: "(none)"}. The debugger needs " +
                        "level 1.2 or newer (configurable interrupt port). Load a newer ps4debug-NG and reconnect.",
                )
            }
        }
        // Session state first: it is what matters while the game is waiting or paused.
        if (phase != DebugPhase.IDLE || error != null) item {
            SectionCard("Session", trailing = { StatusPill(phase.label, phase.tone, modifier = Modifier.testTag("dbgPhase")) }) {
                if (phase == DebugPhase.ATTACHING || phase == DebugPhase.WAITING || phase == DebugPhase.TRACING || phase == DebugPhase.RELEASING) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("dbgStatus")) }
                if (phase == DebugPhase.PAUSED) {
                    Text("The game is paused. It resumes by itself in $idleLeft s.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::step, enabled = !busy, modifier = Modifier.testTag("dbgStep")) { Text("Step") }
                        OutlinedButton(onClick = vm::stepOverOnce, enabled = !busy, modifier = Modifier.testTag("dbgStepOver")) { Text("Step over") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::traceMore, enabled = !busy, modifier = Modifier.testTag("dbgTraceMore")) { Text("Trace $traceSteps") }
                        OutlinedButton(onClick = vm::resume, modifier = Modifier.testTag("dbgResume")) { Text("Resume game") }
                    }
                }
                if (phase == DebugPhase.WAITING || phase == DebugPhase.TRACING || phase == DebugPhase.ATTACHING) {
                    OutlinedButton(onClick = vm::stop, modifier = Modifier.testTag("dbgStop")) { Text("Stop") }
                }
                release?.let { r ->
                    if (r.confirmed) {
                        Text(
                            "✓ Game resumed · breakpoint removed · debugger detached" + if (r.recoveredFromNewConnection) " (released from a new connection)" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.testTag("dbgReleased"),
                        )
                    } else {
                        WarningBanner(
                            "The release could not be confirmed (${r.notes.joinToString("; ")}). If the game stays paused: Settings → Connection " +
                                "diagnostics → Resume process, or reload ps4debug-NG.",
                        )
                    }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
                ErrorText(error)
            }
        }
        hit?.let { h ->
            item {
                SectionCard("Breakpoint hit", subtitle = "${Address.format(h.address)} ${vm.relative(h.address)} · thread ${h.threadName}") {
                    Column(Modifier.testTag("dbgHit"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(h.instruction?.text ?: "(not decoded)", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                        Text("Registers right before it ran:", style = MaterialTheme.typography.bodySmall, color = dim)
                        val used = h.instruction?.let { RegisterSnapshot(h.registers.values, h.address, h.threadName, h.atMillis, false).usedBy(it) }.orEmpty()
                        used.forEach { u -> Text(RegisterFormat.line(u.name, u.value, u.bits), style = mono, modifier = Modifier.testTag("dbgReg-${u.name}")) }
                        TextButton(onClick = { allRegs = !allRegs }) { Text(if (allRegs) "Hide all registers" else "All registers") }
                        if (allRegs) RegisterList(h.registers, emptyList(), mono)
                        h.callStack?.let { frames ->
                            HorizontalDivider()
                            CallStackView(
                                h.address, h.instruction?.text, frames, vm::relative,
                                onOpen = { a -> onOpenCode(a) }, tag = "dbgStack",
                            )
                        }
                    }
                }
            }
        }
        if (steps.size > 1 || (steps.isNotEmpty() && mode != DebugMode.BREAK_ONCE)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Trace · ${steps.size} step(s)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("dbgTraceTitle"))
                        Text("Each row: the instruction about to run and the registers the one before it changed. Tap for all registers.", style = MaterialTheme.typography.bodySmall, color = dim)
                    }
                    OutlinedButton(onClick = { vm.traceText()?.let { copyText(context, "Register trace", it) } }, modifier = Modifier.testTag("dbgCopy")) { Text("Copy") }
                }
            }
            items(steps, key = { it.index }) { s ->
                SectionCard(null) {
                    Column(
                        Modifier.fillMaxWidth().clickable { expanded = if (expanded == s.index) null else s.index }.testTag("dbgStepRow-${s.index}"),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${s.index}", style = mono, color = dim)
                            Text(Address.format(s.address), style = mono)
                            if (s.afterCall) StatusPill("after call")
                        }
                        Text(s.instruction?.text ?: "(not decoded)", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
                        vm.relative(s.address).takeIf { it.isNotBlank() }?.let { Text(it, style = mono, color = dim) }
                        s.changed.forEach { n ->
                            Text("Δ " + RegisterFormat.line(n, s.registers.values[n] ?: 0L, 64), style = mono, color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (expanded == s.index) {
                            HorizontalDivider()
                            RegisterList(s.registers, s.changed, mono)
                            OutlinedButton(onClick = { onOpenCode(vm.open(s)) }) { Text("Open in disassembler") }
                        }
                    }
                }
            }
        }
        item {
            SectionCard("Breakpoint address", step = 1) {
                OutlinedTextField(
                    value = address, onValueChange = vm::setAddress, label = { Text("Instruction address (hex)") }, singleLine = true, enabled = !running,
                    modifier = Modifier.fillMaxWidth().testTag("dbgAddress"),
                )
                Text(
                    "A hardware execution breakpoint: no game code is changed. Pick the instruction in the disassembler (Code → tap it → Breakpoint / trace).",
                    style = MaterialTheme.typography.bodySmall, color = dim,
                )
            }
        }
        item {
            SectionCard("At the breakpoint", step = 2) {
                DebugMode.entries.forEach { m ->
                    ChoiceRow(m.title, m.description, mode == m, { vm.setMode(m) }, enabled = !running, tag = "dbgMode-${m.name}")
                }
                if (mode != DebugMode.BREAK_ONCE) {
                    HorizontalDivider()
                    Selector("Instructions per trace", DebuggerViewModel.STEP_COUNTS, traceSteps, { "$it" }, vm::setTraceSteps, enabled = !running || phase == DebugPhase.PAUSED)
                    LabeledSwitch("Step over calls", stepOver, vm::setStepOver, "Run called functions without recording them.", tag = "dbgStepOverSwitch")
                    LabeledSwitch("Stop when the function returns", stayInFunction, vm::setStayInFunction, "End the trace when the code leaves the function it started in.")
                }
            }
        }
        item {
            SectionCard("Start", step = 3) {
                Button(
                    onClick = { confirm = true }, enabled = !running && selected != null && ng && portOk && selected?.exited == false,
                    modifier = Modifier.testTag("dbgStart"),
                ) { Text(if (phase == DebugPhase.IDLE) "Start" else "Start again") }
                Text(
                    "The debugger attaches to the game and waits up to ${DebuggerViewModel.HIT_TIMEOUT_MS / 1000} s for the breakpoint. " +
                        "Whatever happens, the session ends by resuming the game and detaching. Offline games only.",
                    style = MaterialTheme.typography.bodySmall, color = dim,
                )
            }
        }
    }

    if (confirm) ConfirmDialog(
        title = "Start the debugger?",
        text = when (mode) {
            DebugMode.BREAK_ONCE -> "The game pauses for a moment the first time it reaches ${address.ifBlank { "the address" }} and continues right away."
            DebugMode.TRACE -> "The game pauses at ${address.ifBlank { "the address" }} while up to $traceSteps instructions are recorded one by one (usually a few seconds), then continues."
            DebugMode.MANUAL -> "The game stays paused at ${address.ifBlank { "the address" }} until you resume it (or for ${DebuggerViewModel.IDLE_SECONDS} s without input). Sound and network of the game stop meanwhile."
        } + "\n\nNot verified on a real PS4 yet. If the game stays frozen: Settings → Connection diagnostics → Resume process.",
        confirmLabel = "Start",
        onConfirm = { confirm = false; vm.start() },
        onDismiss = { confirm = false },
    )
}

/** All general-purpose registers, one per line in hex and decimal; [changed] ones are highlighted. */
@Composable
private fun RegisterList(regs: Registers, changed: List<String>, mono: androidx.compose.ui.text.TextStyle) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        regs.general.forEach { (n, v) ->
            Text(
                RegisterFormat.line(n, v, 64), style = mono,
                color = if (n in changed) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 1.dp),
            )
        }
    }
}
