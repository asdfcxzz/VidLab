package com.vidlab.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val isVideo: Boolean = false
)

@Serializable
data class CastMember(
    val name: String,
    val role: String,
    val profileUrl: String? = null
)

@Serializable
data class MediaMetadata(
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val overview: String? = null,
    val cast: List<CastMember> = emptyList()
)

@Serializable
data class ProcessedEpisode(
    val file: MediaFile,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String,
    val thumbnail: String? = null
)

@Serializable
data class SeasonGroup(
    val seasonNumber: Int,
    val episodes: List<ProcessedEpisode>
)

@Serializable
data class ScrapedMediaItem(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val releaseYear: String? = null,
    val isSeries: Boolean = false,
    val primaryFile: MediaFile,
    val seasons: List<SeasonGroup> = emptyList(),
    val metadata: MediaMetadata? = null,
    val needsManualReview: Boolean = false
) {
    val totalEpisodesCount: Int
        get() = seasons.sumOf { it.episodes.size }
}

@Serializable
data class MediaFeedSection(
    val title: String,
    val items: List<ScrapedMediaItem>
)

@Serializable
data class TmdbSearchResult(
    val id: Int,
    val title: String,
    val mediaType: String,
    val year: String?,
    val posterUrl: String?
)

data class AmbiguousPrompt(
    val mediaKey: String,
    val rawName: String,
    val candidates: List<TmdbSearchResult>
)

@Serializable
data class RenameOperation(
    val oldPath: String,
    val newPath: String,
    val fileName: String,
    val newFileName: String
)
