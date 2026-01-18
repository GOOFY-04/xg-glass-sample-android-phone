package com.universalglasses.appcontract

import com.universalglasses.core.GlassesClient
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.GlassesModel
import kotlinx.coroutines.CoroutineScope

/**
 * "Universal app" contract.
 *
 * Goal: let developers write the same business logic against [GlassesClient] for different glasses models,
 * while different hosts (phone app vs on-glasses app) render UI and trigger actions.
 *
 * This module only defines the *entry interfaces*. Build automation (e.g., generating a RayNeo host APK,
 * copying it into the phone app, installing it on connect) is implemented separately.
 */

/** Where this code is currently running. */
enum class HostKind {
    /** Running inside a phone-side Android app (controls peripheral glasses and installs on-glasses apps). */
    PHONE,

    /** Running inside an on-glasses Android app. */
    GLASSES,
}

/** Host + device context provided to the developer entry. */
data class HostEnvironment(
    val hostKind: HostKind,
    val model: GlassesModel,
)

/**
 * Execution context passed to user commands.
 *
 * - [client] is the active [GlassesClient] for the selected model.
 * - [environment] tells you whether you're running on phone or on glasses.
 * - [log] lets hosts surface logs (phone UI log view / glasses overlay, etc).
 */
data class UniversalAppContext(
    val environment: HostEnvironment,
    val client: GlassesClient,
    val scope: CoroutineScope? = null,
    val log: (String) -> Unit = {},
    /**
     * Optional host callback for captured images.
     *
     * - Phone host can show a preview.
     * - Glasses host can ignore it (null).
     */
    val onCapturedImage: ((CapturedImage) -> Unit)? = null,
)

/** A user-facing action (host renders it as a button/menu item/gesture selection, etc). */
interface UniversalCommand {
    val id: String
    val title: String

    suspend fun run(ctx: UniversalAppContext): Result<Unit>
}

/**
 * Developer-implemented entry point.
 *
 * Hosts (phone and/or glasses) instantiate the entry (via reflection or explicit wiring) and call
 * [commands] to render actions.
 */
interface UniversalAppEntry {
    /** Stable identifier for analytics / routing (e.g., "auto_solver"). */
    val id: String

    /** Display name for UI (e.g., "Auto Solver"). */
    val displayName: String

    /**
     * Provide the actions this app supports in the given host/device environment.
     *
     * Example: for RayNeo, you might return capture/display actions for [HostKind.GLASSES],
     * and return an empty list for [HostKind.PHONE] (phone is only the installer).
     */
    fun commands(env: HostEnvironment): List<UniversalCommand>
}


