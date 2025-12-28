package com.munch.streamer.data.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class MatchDto(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "category") val category: String,
    @Json(name = "date") val date: Long = 0L,
    @Json(name = "poster") val poster: String? = null,
    @Json(name = "popular") val popular: Boolean = false,
    @Json(name = "teams") val teams: TeamsDto? = null,
    @Json(name = "sources") val sources: List<SourceDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TeamsDto(
    @Json(name = "home") val home: TeamDto? = null,
    @Json(name = "away") val away: TeamDto? = null
)

@JsonClass(generateAdapter = true)
data class TeamDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "badge") val badge: String? = null
)

@JsonClass(generateAdapter = true)
data class SourceDto(
    @Json(name = "source") val source: String,
    @Json(name = "id") val id: String
)

@JsonClass(generateAdapter = true)
data class StreamDto(
    @Json(name = "id") val id: String,
    @Json(name = "streamNo") val streamNo: Int? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "hd") val hd: Boolean = false,
    @Json(name = "embedUrl") val embedUrl: String? = null,
    @Json(name = "source") val source: String? = null
)

@JsonClass(generateAdapter = true)
data class SportDto(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String
)
