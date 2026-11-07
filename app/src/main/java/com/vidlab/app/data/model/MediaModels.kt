package com.vidlab.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MediaFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L
) {
    val isVideo: Boolean
        get() = !isDirectory && (
            name.endsWith(".mkv", true) ||
            name.endsWith(".mp4", true) ||
            name.endsWith(".webm", true) ||
            name.endsWith(".avi", true)
        )
}

@Serializable
data class MediaMetadata(
    val id: String = "",
    val title: String,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val releaseYear: String? = null,
    val genres: List<String> = emptyList(),
    val rating: String? = null
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
    val metadata: MediaMetadata? = null
) {
    val totalEpisodesCount: Int
        get() = if (isSeries) seasons.sumOf { it.episodes.size } else 1
}

@Serializable
data class MediaFeedSection(
    val title: String,
    val items: List<ScrapedMediaItem>
)
