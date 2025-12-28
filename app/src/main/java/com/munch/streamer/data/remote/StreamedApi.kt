package com.munch.streamer.data.remote

import com.munch.streamer.data.remote.model.MatchDto
import com.munch.streamer.data.remote.model.SportDto
import com.munch.streamer.data.remote.model.StreamDto
import retrofit2.http.GET
import retrofit2.http.Path

interface StreamedApi {
    @GET("api/matches/{sport}")
    suspend fun getMatchesForSport(
        @Path("sport") sport: String
    ): List<MatchDto>

    @GET("api/matches/live")
    suspend fun getLiveMatches(): List<MatchDto>

    @GET("api/matches/live/popular")
    suspend fun getLivePopularMatches(): List<MatchDto>

    @GET("api/stream/{source}/{id}")
    suspend fun getStreams(
        @Path("source") source: String,
        @Path("id") id: String
    ): List<StreamDto>

    @GET("api/sports")
    suspend fun getSports(): List<SportDto>
}
