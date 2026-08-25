package com.nuvio.app.features.discordrpc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DiscordActivity(
    val details: String? = null,
    val state: String? = null,
    val timestamps: DiscordActivityTimestamps? = null,
    val assets: DiscordActivityAssets? = null,
)

@Serializable
internal data class DiscordActivityTimestamps(
    val start: Long? = null,
)

@Serializable
internal data class DiscordActivityAssets(
    @SerialName("large_image") val largeImage: String? = null,
    @SerialName("large_text") val largeText: String? = null,
)
