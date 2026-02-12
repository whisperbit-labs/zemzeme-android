package com.roman.zemzeme.geohash

/**
 * Levels of location channels mapped to geohash precisions.
 * Direct port from iOS implementation for 100% compatibility
 */
enum class GeohashChannelLevel(val precision: Int, val displayName: String) {
    BUILDING(8, "Building"), // iOS: precision 8 for building-level (used for Location Notes)
    BLOCK(7, "Block"),
    NEIGHBORHOOD(6, "Neighborhood"),
    CITY(5, "City"),
    PROVINCE(4, "Province"),
    REGION(2, "REGION");
    
    companion object {
        fun allCases(): List<GeohashChannelLevel> = values().toList()
    }
}

/**
 * A computed geohash channel option.
 * Direct port from iOS implementation
 */
data class GeohashChannel(
    val level: GeohashChannelLevel,
    val geohash: String
) {
    val id: String get() = "${level.name}-$geohash"
    
    val displayName: String get() = "${level.displayName} • $geohash"
}

/**
 * Identifier for current public chat channel (mesh or a location geohash).
 * Direct port from iOS implementation
 */
sealed class ChannelID {
    object Mesh : ChannelID()
    data class Location(val channel: GeohashChannel) : ChannelID() {
        companion object {
            fun fromPersisted(levelName: String, geohash: String): Location? {
                return try {
                    val level = GeohashChannelLevel.valueOf(levelName)
                    Location(GeohashChannel(level, geohash))
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }
    
    /**
     * Human readable name for UI.
     */
    val displayName: String
        get() = when (this) {
            is Mesh -> "Bluetooth"
            is Location -> channel.displayName
        }
    
    /**
     * Nostr tag value for scoping (geohash), if applicable.
     */
    val nostrGeohashTag: String?
        get() = when (this) {
            is Mesh -> null
            is Location -> channel.geohash
        }
    
    /**
     * True when this channel uses BLE Mesh as its transport (i.e. not a geohash/internet channel).
     */
    val isMesh: Boolean get() = this is Mesh

    override fun equals(other: Any?): Boolean {
        return when {
            this is Mesh && other is Mesh -> true
            this is Location && other is Location -> this.channel == other.channel
            else -> false
        }
    }
    
    override fun hashCode(): Int {
        return when (this) {
            is Mesh -> "mesh".hashCode()
            is Location -> channel.hashCode()
        }
    }
}

/**
 * Returns true when the selected channel is BLE Mesh (including the null default).
 * null means no explicit selection, which defaults to the main #mesh timeline.
 */
val ChannelID?.isMeshView: Boolean get() = this == null || this.isMesh
