package com.irondroid.engine.action

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Who made a call: the player through the screens, or the AI narrator (RF-API-09). */
enum class Caller { PLAYER, AI }

/**
 * One request to run an action. [confirmed] says the player already accepted it, as sensitive
 * actions need (RF-API-08).
 */
data class ActionCall(
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val caller: Caller,
    val confirmed: Boolean = false,
)

/**
 * Runs one action. It gets arguments that already fit the action's parameters; it checks the rules
 * and the game state itself and refuses with [ActionRejectedException] before changing anything.
 * It returns what happened; a result with a pending choice stops until the player decides it.
 */
fun interface ActionHandler {
    fun run(arguments: ActionArguments, call: ActionCall): ActionResult
}

/** What came of a call. */
sealed interface DispatchResult {
    val call: ActionCall

    /** The action ran. If [result] brings a pending choice, it is now open in [ActionDispatcher.choices]. */
    data class Success(override val call: ActionCall, val action: ActionDefinition, val result: ActionResult) : DispatchResult

    /** The action did not run, because of [error]. */
    data class Failure(override val call: ActionCall, val error: ActionError) : DispatchResult

    /** A sensitive action waits for the player: dispatch it again with `confirmed = true` once they accept. */
    data class NeedsConfirmation(override val call: ActionCall, val action: ActionDefinition) : DispatchResult
}

/** Hears about every action that ran, such as the autosave (IRON-34) or the turn history (IRON-41). */
fun interface ActionListener {
    fun afterAction(result: DispatchResult.Success)
}

/**
 * The single door of the actions interface (RF-API-04): the screens and the AI make every call
 * through it, so both get the same checks (RF-API-05). Calls run one at a time.
 *
 * Each call goes: known action, valid arguments, no open choice in the way, player confirmation if
 * sensitive, handler, listeners. While the player has a choice open (RF-API-07) only read actions and
 * those that answer the choice run. Listeners run after every success; a listener that fails does not
 * undo the action.
 */
class ActionDispatcher(val catalog: ActionCatalog, handlers: Map<String, ActionHandler>) {
    private val handlers = handlers.toMap()
    private val listeners = mutableListOf<ActionListener>()
    private val lock = Any()

    /** The choice the player has to make, opened by the results of the actions. */
    val choices = PendingChoices()

    init {
        val names = catalog.actions.map { it.name }.toSet()
        val errors = (names - handlers.keys).map { "Action $it has no handler" } +
            (handlers.keys - names).map { "Handler $it has no action in the catalog" }
        if (errors.isNotEmpty()) throw ActionCatalogException(errors)
    }

    fun addListener(listener: ActionListener) = synchronized(lock) { listeners += listener }

    fun removeListener(listener: ActionListener) = synchronized(lock) { listeners -= listener }

    fun dispatch(call: ActionCall): DispatchResult = synchronized(lock) {
        val action = catalog[call.name] ?: return fail(
            call,
            ActionErrorCode.UNKNOWN_ACTION,
            "There is no action called ${call.name}",
            suggestion = closestName(call.name)?.let { "Did you mean $it?" },
        )
        val problems = action.parameters.validate(call.arguments)
        if (problems.isNotEmpty()) {
            return fail(call, ActionErrorCode.INVALID_ARGUMENTS, "The arguments of ${action.name} are not valid", problems)
        }
        val open = choices.current
        if (open != null && action.permission != ActionPermission.READ && !action.answersChoice) {
            return fail(
                call,
                ActionErrorCode.INVALID_STATE,
                "The player has choice ${open.id} open and must decide it first",
                suggestion = "Wait for the player; you can mark your suggestion with suggest_option",
            )
        }
        if (action.permission.requiresConfirmation && !call.confirmed) return DispatchResult.NeedsConfirmation(call, action)

        val result = try {
            handlers.getValue(action.name).run(ActionArguments(call.arguments), call)
        } catch (e: ActionRejectedException) {
            return DispatchResult.Failure(call, e.error)
        } catch (e: Exception) {
            return fail(call, ActionErrorCode.INTERNAL, "${action.name} failed inside the app: ${e.message ?: e::class.simpleName}")
        }
        result.pendingChoice?.let { next ->
            choices.current?.let { still ->
                return fail(call, ActionErrorCode.INTERNAL, "${action.name} opened a choice while ${still.id} is still open")
            }
            choices.open(next)
        }
        val success = DispatchResult.Success(call, action, result)
        for (listener in listeners.toList()) runCatching { listener.afterAction(success) }
        success
    }

    /**
     * Dispatches a call whose arguments arrive as JSON text, as providers send them. Text that is
     * not a JSON object (or is empty, meaning no arguments) is an argument error, not a crash.
     */
    fun dispatch(name: String, arguments: String, caller: Caller, confirmed: Boolean = false): DispatchResult {
        val parsed = if (arguments.isBlank()) {
            JsonObject(emptyMap())
        } else {
            try {
                Json.parseToJsonElement(arguments) as? JsonObject
            } catch (_: SerializationException) {
                null
            }
        }
        val call = ActionCall(name, parsed ?: JsonObject(emptyMap()), caller, confirmed)
        return if (parsed == null) {
            fail(call, ActionErrorCode.INVALID_ARGUMENTS, "The arguments of $name must be a JSON object", listOf("received: $arguments"))
        } else {
            dispatch(call)
        }
    }

    private fun fail(call: ActionCall, code: ActionErrorCode, message: String, details: List<String> = emptyList(), suggestion: String? = null) =
        DispatchResult.Failure(call, ActionError(code, message, details, suggestion))

    /** The catalog name nearest to [name], when it is close enough to be a typo. */
    private fun closestName(name: String): String? =
        catalog.actions.map { it.name }
            .map { it to editDistance(it, name.lowercase()) }
            .filter { (candidate, distance) -> distance <= maxOf(2, candidate.length / 4) }
            .minByOrNull { it.second }?.first

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }
}
