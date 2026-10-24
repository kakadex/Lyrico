package com.lonx.lyrico.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class GitHubReleaseDTO(
    val tag_name: String,
    val body: String? = null,
    val html_url: String,
    val assets: List<ReleaseAssetDTO> = emptyList()
)

@Serializable
data class ReleaseAssetDTO(
    val name: String = "",
    val browser_download_url: String = ""
)