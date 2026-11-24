package io.github.hazymorning.amsel.data

/**
 * What the signed-in user may change. Gated on scopes, never on the role name: 2.11 has custom
 * roles, and an unknown role grants nothing, so the gate fails closed.
 */
data class UserPermissions(val scopes: Set<String>) {
    /** Favourites and playlist edits. A guest can play but not change the library. */
    val canWriteLibrary: Boolean get() = "*" in scopes || "library.write" in scopes

    companion object {
        fun of(role: String?, scopesByRole: Map<String, List<String>>): UserPermissions =
            UserPermissions(role?.let { scopesByRole[it] }.orEmpty().toSet())
    }
}
