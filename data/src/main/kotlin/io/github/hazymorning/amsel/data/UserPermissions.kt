package io.github.hazymorning.amsel.data

// Gated on scopes, never on the role name: 2.11 has custom roles, and an unknown role grants nothing.
// See docs/API-NOTES.md, "Scopes".
data class UserPermissions(val scopes: Set<String>) {
    val canWriteLibrary: Boolean get() = "*" in scopes || "library.write" in scopes

    companion object {
        fun of(role: String?, scopesByRole: Map<String, List<String>>): UserPermissions =
            UserPermissions(role?.let { scopesByRole[it] }.orEmpty().toSet())
    }
}
