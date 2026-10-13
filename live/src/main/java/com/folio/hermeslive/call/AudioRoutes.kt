package com.folio.hermeslive.call

/** Kinds of audio route Hermes Live offers. */
enum class RouteKind(val label: String) {
    Earpiece("Phone"),
    Speaker("Speaker"),
    Wired("Wired headset"),
    Usb("USB headset"),
    Bluetooth("Bluetooth"),
    HearingAid("Hearing aid"),
    Other("Other"),
    ;

    /** True for anything worn: music in the gaps comes back at full volume there. */
    val isHeadset: Boolean get() = this == Wired || this == Usb || this == Bluetooth || this == HearingAid
}

/** One communication device. [id] is `AudioDeviceInfo.id`; [name] what the user sees. */
data class RouteOption(val id: Int, val kind: RouteKind, val name: String) {
    val label: String get() = if (kind == RouteKind.Bluetooth && name.isNotBlank()) name else kind.label
}

/** Where the call goes without a headset, and after one disconnects. */
enum class FallbackRoute { Speaker, Earpiece }

/** The route to use, and whether the user picked it by hand. */
data class RouteChoice(val route: RouteOption?, val manual: Boolean)

/**
 * Route rules (pure). Automatic order: Bluetooth, wired, USB, hearing aid, then the fallback
 * (speaker by default). A newly connected headset always takes over; when the current route
 * disappears the call goes back to the automatic pick. A hand-picked route stays otherwise.
 */
object RouteSelector {
    private val HEADSET_ORDER = listOf(RouteKind.Bluetooth, RouteKind.Wired, RouteKind.Usb, RouteKind.HearingAid)

    /** The automatic route among [available]. */
    fun auto(available: List<RouteOption>, fallback: FallbackRoute): RouteOption? {
        for (kind in HEADSET_ORDER) available.firstOrNull { it.kind == kind }?.let { return it }
        return fallbackOf(available, fallback)
    }

    /** The phone's own route for [fallback] (speaker or earpiece), else whatever is left. */
    fun fallbackOf(available: List<RouteOption>, fallback: FallbackRoute): RouteOption? {
        val wanted = if (fallback == FallbackRoute.Speaker) RouteKind.Speaker else RouteKind.Earpiece
        val other = if (wanted == RouteKind.Speaker) RouteKind.Earpiece else RouteKind.Speaker
        return available.firstOrNull { it.kind == wanted }
            ?: available.firstOrNull { it.kind == other }
            ?: available.firstOrNull()
    }

    /** The device list changed from [before] to [after] while on [current]. */
    fun onDevicesChanged(
        before: List<RouteOption>,
        after: List<RouteOption>,
        current: RouteChoice,
        fallback: FallbackRoute,
    ): RouteChoice {
        val beforeIds = before.map { it.id }.toSet()
        val newHeadset = after.filter { it.id !in beforeIds && it.kind.isHeadset }
            .minByOrNull { HEADSET_ORDER.indexOf(it.kind) }
        if (newHeadset != null) return RouteChoice(newHeadset, manual = false)
        val still = current.route?.let { cur -> after.firstOrNull { it.id == cur.id } }
        if (still != null) return current.copy(route = still)
        return RouteChoice(auto(after, fallback), manual = false)
    }

    /** Headphones were pulled out (`ACTION_AUDIO_BECOMING_NOISY`): go to the phone's [fallback] route. */
    fun onNoisy(available: List<RouteOption>, fallback: FallbackRoute): RouteChoice =
        RouteChoice(fallbackOf(available.filterNot { it.kind.isHeadset }, fallback), manual = false)
}
