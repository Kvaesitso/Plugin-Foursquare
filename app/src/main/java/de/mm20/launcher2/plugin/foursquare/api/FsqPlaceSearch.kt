package de.mm20.launcher2.plugin.foursquare.api

import kotlinx.serialization.Serializable

@Serializable
data class FsqPlaceSearch(
    val results: List<FsqPlace>? = null,
)

