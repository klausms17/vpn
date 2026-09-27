package com.klausms.vpn.data

import com.klausms.vpn.core.ParsedProfile

// Pure changes to the saved server list, used by the UI as transforms of
// AppRepository.updateProfiles: they run on the state read from disk, under
// the store's lock, so what the VPN process or a parallel import saved
// meanwhile is taken into account.

/** [id] selected, or unchanged when that server is gone (the VPN process replaced the list meanwhile). */
internal fun ProfilesState.withSelected(id: String): ProfilesState =
    if (selectedId != id && profiles.any { it.id == id }) copy(selectedId = id) else this

/** [added] appended; the selection is kept if it still names a server, else the first new one. */
internal fun ProfilesState.withAdded(added: List<StoredProfile>): ProfilesState =
    if (added.isEmpty()) this else copy(profiles = profiles + added, selectedId = selected?.id ?: added.first().id)

/** Keys not saved yet, each once (a message may repeat a key, e.g. in a quote). */
internal fun freshKeys(ready: List<ParsedProfile>, saved: Set<String>): List<ParsedProfile> =
    ready.filter { it.link == null || it.link !in saved }.distinctBy { it.link ?: it.outbounds.toString() }

/**
 * The keys of [ready] that this state does not have yet, appended as own
 * keys; returns the new state and the servers actually added (none when
 * all were saved already, and then the state is this one).
 */
internal fun ProfilesState.withNewKeys(ready: List<ParsedProfile>): Pair<ProfilesState, List<StoredProfile>> {
    val added = freshKeys(ready, profiles.mapNotNullTo(HashSet()) { it.link }).map { it.toStored(null) }
    return withAdded(added) to added
}

/** Server [id] named [name]. */
internal fun ProfilesState.renamed(id: String, name: String): ProfilesState =
    copy(profiles = profiles.map { if (it.id == id) it.copy(name = name) else it })

/** Server [id] removed; if it was the selected one, the first remaining server is selected. */
internal fun ProfilesState.withoutProfile(id: String): ProfilesState {
    val rest = profiles.filterNot { it.id == id }
    return copy(profiles = rest, selectedId = if (selectedId == id) rest.firstOrNull()?.id else selectedId)
}

/**
 * Subscription [id] removed with all its servers; a selection outside it
 * is kept, otherwise the first remaining server is selected.
 */
internal fun ProfilesState.withoutSubscription(id: String): ProfilesState {
    val rest = profiles.filterNot { it.subscriptionId == id }
    return copy(
        profiles = rest,
        subscriptions = subscriptions.filterNot { it.id == id },
        selectedId = if (rest.any { it.id == selectedId }) selectedId else rest.firstOrNull()?.id,
    )
}
