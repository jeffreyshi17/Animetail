package eu.kanade.tachiyomi.ui.player.cast

/** Which implementation talks to cast receivers. */
enum class CastBackend {
    /** Google Cast when Play services is fully available, otherwise [DIRECT]. */
    AUTO,

    /** The Google Cast SDK; needs working Google Play services. */
    GOOGLE_CAST,

    /** Our own CastV2 client; works without Play services. */
    DIRECT,
    ;

    companion object {
        /** Resolves the user's choice to the backend that runs: never [AUTO]. */
        fun resolve(preference: CastBackend, playServicesAvailable: Boolean): CastBackend = when (preference) {
            AUTO -> if (playServicesAvailable) GOOGLE_CAST else DIRECT
            else -> preference
        }
    }
}
