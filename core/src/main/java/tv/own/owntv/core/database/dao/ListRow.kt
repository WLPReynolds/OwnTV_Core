package tv.own.owntv.core.database.dao

/** The few columns the playlist-lists export needs from a channel, movie or series row. */
data class ListRow(
    val remoteId: String?,
    val name: String,
    val categoryId: Long?,
)
