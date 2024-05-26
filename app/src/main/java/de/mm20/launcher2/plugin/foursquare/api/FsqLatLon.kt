package de.mm20.launcher2.plugin.foursquare.api

import kotlinx.serialization.Serializable

@Serializable
data class FsqLatLon(
    val latitude: Double,
    val longitude: Double,
)