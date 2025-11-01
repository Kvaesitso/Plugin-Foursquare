package de.mm20.launcher2.plugin.foursquare.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FsqPlace(
    @SerialName("fsq_place_id")
    val fsqPlaceId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val categories: List<FsqPlaceCategory>? = null,
    val name: String? = null,
    val location: FsqPlaceLocation? = null,
    val tel: String? = null,
    val email: String? = null,
    val website: String? = null,
    val hours: FsqPlaceHours? = null,
    val rating: Float? = null,
)

@Serializable
data class FsqPlaceGeocodes(
    @SerialName("main")
    val main: FsqLatLon? = null,
    @SerialName("drop_off")
    val dropOff: FsqLatLon? = null,
    @SerialName("roof")
    val roof: FsqLatLon? = null,
    @SerialName("front_door")
    val frontDoor: FsqLatLon? = null,
    val road: FsqLatLon? = null,
)

@Serializable
data class FsqPlaceLocation(
    val address: String? = null,
    val country: String? = null,
    @SerialName("formatted_address")
    val formattedAddress: String? = null,
    val locality: String? = null,
    val postcode: String? = null,
    val region: String? = null,
)

@Serializable
data class FsqPlaceCategory(
    @SerialName("fsq_category_id")
    val fsqCategoryId: String? = null,
    val name: String? = null,
    @SerialName("short_name")
    val shortName: String? = null,
    @SerialName("plural_name")
    val pluralName: String? = null,
    val icon: FsqPlaceCategoryIcon? = null,
)

@Serializable
data class FsqPlaceCategoryIcon(
    val prefix: String? = null,
    val suffix: String? = null,
)

@Serializable
data class FsqPlaceHours(
    val display: String? = null,
    @SerialName("is_local_holiday")
    val isLocalHoliday: Boolean? = null,
    @SerialName("open_now")
    val openNow: Boolean? = null,
    val regular: List<FsqPlaceHoursRegular>? = null,
)

@Serializable
data class FsqPlaceHoursRegular(
    val close: String? = null,
    val day: Int? = null,
    val open: String? = null,
)