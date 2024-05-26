package de.mm20.launcher2.plugin.foursquare

import de.mm20.launcher2.plugin.config.SearchPluginConfig
import de.mm20.launcher2.plugin.config.StorageStrategy
import de.mm20.launcher2.plugin.foursquare.api.FsqLatLon
import de.mm20.launcher2.plugin.foursquare.api.FsqPlace
import de.mm20.launcher2.plugin.foursquare.api.FsqPlaceCategory
import de.mm20.launcher2.plugin.foursquare.api.FsqPlaceHoursRegular
import de.mm20.launcher2.sdk.locations.Location
import de.mm20.launcher2.sdk.locations.LocationPluginProvider
import de.mm20.launcher2.sdk.locations.LocationQuery
import de.mm20.launcher2.search.location.Address
import de.mm20.launcher2.search.location.Attribution
import de.mm20.launcher2.search.location.LocationIcon
import de.mm20.launcher2.search.location.OpeningHours
import de.mm20.launcher2.search.location.OpeningSchedule
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime

private val Fields =
    setOf(
        "fsq_id",
        "name",
        "geocodes",
        "location",
        "categories",
        "tel",
        "email",
        "website",
        "hours",
        "rating"
    )

class FoursquareLocationProvider : LocationPluginProvider(
    config = SearchPluginConfig(
        storageStrategy = StorageStrategy.Deferred,
    )
) {
    private lateinit var apiClient: FoursquareApiClient

    override fun onCreate(): Boolean {
        apiClient = FoursquareApiClient(context!!.getString(R.string.api_key))
        return true
    }

    override suspend fun get(id: String): Location? {
        return apiClient.placeById(
            id,
            fields = Fields
        )?.toLocation()

    }

    override suspend fun search(query: LocationQuery, allowNetwork: Boolean): List<Location> {
        if (!allowNetwork) return emptyList()

        val results = apiClient.placesSearch(
            query.query,
            ll = FsqLatLon(
                latitude = query.userLatitude,
                longitude = query.userLongitude,
            ),
            radius = query.searchRadius.toInt(),
            fields = Fields,
        )

        return results.results?.mapNotNull { it.toLocation() } ?: emptyList()
    }
}

private fun FsqPlace.toLocation(): Location? {
    return Location(
        id = fsqId ?: return null,
        label = name ?: return null,
        address = Address(
            address = location?.address,
            country = location?.country,
            state = location?.region,
            city = location?.locality,
            postalCode = location?.postcode,
        ),
        latitude = geocodes?.main?.latitude ?: return null,
        longitude = geocodes.main.longitude,
        phoneNumber = tel,
        websiteUrl = website,
        userRating = rating?.div(10f),
        attribution = Attribution(
            text = "Foursquare",
            url = "https://foursquare.com/v/$fsqId",
            iconUrl = null,
        ),
        icon = categories?.firstOrNull()?.toIcon(),
        category = categories?.firstOrNull()?.name,
        openingSchedule = hours?.regular?.let {
            OpeningSchedule.Hours(
                openingHours = it.mapNotNull { it.toOpeningHours() },
            )
        }
    )
}

private fun FsqPlaceHoursRegular.toOpeningHours(): OpeningHours? {
    if (day == null || day !in 1..7) return null
    val day = DayOfWeek.of(day)

    if (open == null || open.length != 4) return null
    val openTime = try {
        val hh = open.substring(0, 2).toIntOrNull() ?: return null
        val mm = open.substring(2).toIntOrNull() ?: return null
        LocalTime.of(hh, mm)
    } catch (e: DateTimeException) {
        return null
    }

    if (close == null || close.length < 4) return null
    val closesNextDay = close.startsWith("+")
    val closeTime = try {
        if (closesNextDay) {
            val hh = close.substring(1, 3).toIntOrNull() ?: return null
            val mm = close.substring(3).toIntOrNull() ?: return null
            LocalTime.of(hh, mm)
        } else {
            val hh = close.substring(0, 2).toIntOrNull() ?: return null
            val mm = close.substring(2).toIntOrNull() ?: return null
            LocalTime.of(hh, mm)
        }
    } catch (e: DateTimeException) {
        return null
    }

    val duration = Duration.between(openTime, closeTime).plusDays(if (closesNextDay) 1 else 0)

    return OpeningHours(
        dayOfWeek = day,
        startTime = openTime,
        duration = duration,
    )
}

private fun FsqPlaceCategory.toIcon(): LocationIcon? {
    return when (id) {
        in 10027..10031 -> LocationIcon.Museum
        10004 -> LocationIcon.ArtGallery
        10032 -> LocationIcon.NightClub
        10001, 10058 -> LocationIcon.AmusementPark
        10008 -> LocationIcon.Casino
        in 10024..10026 -> LocationIcon.MovieTheater
        10051, in 10060..10067 -> LocationIcon.Stadium
        10035, 10036, 10038, 10043 -> LocationIcon.Theater
        10037, in 10039..10043 -> LocationIcon.Music
        in 12009..12012, in 12049..12063 -> LocationIcon.School
        12013, 12125 -> LocationIcon.University
        12067 -> LocationIcon.Courthouse
        12080 -> LocationIcon.Library
        12075 -> LocationIcon.PostOffice
        12092 -> LocationIcon.PublicBathroom
        12099 -> LocationIcon.BuddhistTemple
        12101 -> LocationIcon.Church
        12103 -> LocationIcon.HinduTemple
        12106 -> LocationIcon.Mosque
        12110 -> LocationIcon.Synagogue
        in 13003..13025, 13389 -> LocationIcon.Bar
        in 13032..13037 -> LocationIcon.Cafe
        13031 -> LocationIcon.Burger
        13046 -> LocationIcon.IceCream
        13064 -> LocationIcon.Pizza
        13145 -> LocationIcon.FastFood
        13288 -> LocationIcon.Kebab
        13342 -> LocationIcon.Soup
        13272 -> LocationIcon.Ramen
        in 13000..13392 -> LocationIcon.Restaurant
        15014, 15058, 15059 -> LocationIcon.Hospital
        in 15027..15050 -> LocationIcon.Physician
        in 15000..15059 -> LocationIcon.Clinic
        16015 -> LocationIcon.Forest
        in 16032..16039 -> LocationIcon.Park
        17142, 17069, 17070 -> LocationIcon.Supermarket
        17034 -> LocationIcon.DiscountStore
        17056 -> LocationIcon.Florist
        17100 -> LocationIcon.Kiosk
        in 17082..17088 -> LocationIcon.FurnitureStore
        in 17000..17146 -> LocationIcon.Shopping
        17018, 17019, 17022 -> LocationIcon.BookStore
        17042, 17043 -> LocationIcon.ClothingStore
        17045 -> LocationIcon.JewelryStore
        17076 -> LocationIcon.LiquorStore
        17110 -> LocationIcon.PetStore
        15024 -> LocationIcon.Optometrist
        13002 -> LocationIcon.Bakery
        17145 -> LocationIcon.Pharmacy
        17029 -> LocationIcon.ConvenienceStore
        17114 -> LocationIcon.ShoppingMall
        12072 -> LocationIcon.Police
        12071 -> LocationIcon.FireDepartment
        in 11042..11055 -> LocationIcon.Bank
        in 19009..19019 -> LocationIcon.Hotel
        11011 -> LocationIcon.CarWash
        11010 -> LocationIcon.CarRepair
        19048 -> LocationIcon.CarRental
        in 11009..11018 -> LocationIcon.Car
        19042, 19043 -> LocationIcon.Bus
        19046 -> LocationIcon.Subway
        19047 -> LocationIcon.Train
        19050, 19063 -> LocationIcon.Tram
        19064 -> LocationIcon.Boat
        19065 -> LocationIcon.CableCar
        19020 -> LocationIcon.Parking
        in 19031..19041 -> LocationIcon.Airport
        19006 -> LocationIcon.ChargingStation
        19007 -> LocationIcon.GasStation
        else -> null
    }
}