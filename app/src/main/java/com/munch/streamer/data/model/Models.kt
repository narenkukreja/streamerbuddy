package com.munch.streamer.data.model

data class Match(
    val id: String,
    val title: String,
    val category: String,
    val date: Long,
    val poster: String?,
    val popular: Boolean,
    val teams: Teams?,
    val sources: List<StreamSource>,
    val isLive: Boolean
)

data class Teams(
    val home: Team?,
    val away: Team?
)

data class Team(
    val name: String?,
    val badge: String?
)

data class StreamSource(
    val source: String,
    val id: String,
    val language: String? = null
) : java.io.Serializable

data class StreamItem(
    val id: String,
    val streamNo: Int?,
    val language: String?,
    val hd: Boolean,
    val embedUrl: String?,
    val source: String?
)

data class Sport(
    val id: String,
    val name: String
)
