package de.mm20.launcher2.plugin.foursquare

import android.content.Intent
import android.net.Uri
import android.util.Log
import de.mm20.launcher2.plugin.config.QueryPluginConfig
import de.mm20.launcher2.plugin.config.StorageStrategy
import de.mm20.launcher2.plugin.foursquare.api.FsqLatLon
import de.mm20.launcher2.plugin.foursquare.api.FsqPlace
import de.mm20.launcher2.plugin.foursquare.api.FsqPlaceCategory
import de.mm20.launcher2.plugin.foursquare.api.FsqPlaceHoursRegular
import de.mm20.launcher2.sdk.PluginState
import de.mm20.launcher2.sdk.base.RefreshParams
import de.mm20.launcher2.sdk.base.SearchParams
import de.mm20.launcher2.sdk.locations.Location
import de.mm20.launcher2.sdk.locations.LocationProvider
import de.mm20.launcher2.sdk.locations.LocationQuery
import de.mm20.launcher2.search.location.Address
import de.mm20.launcher2.search.location.Attribution
import de.mm20.launcher2.search.location.LocationIcon
import de.mm20.launcher2.search.location.OpeningHours
import de.mm20.launcher2.search.location.OpeningSchedule
import kotlinx.coroutines.flow.first
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalTime
import kotlin.time.Duration.Companion.days

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

// https://docs.foursquare.com/developer/reference/localization-v3
private val Languages =
    setOf("en", "es", "fr", "de", "it", "ja", "th", "tr", "ko", "ru", "pt", "id")

class FoursquareLocationProvider : LocationProvider(
    config = QueryPluginConfig(
        storageStrategy = StorageStrategy.StoreCopy,
    )
) {
    private lateinit var apiClient: FoursquareApiClient

    override fun onCreate(): Boolean {
        apiClient = FoursquareApiClient(context!!)
        return true
    }

    override suspend fun refresh(item: Location, params: RefreshParams): Location? {
        if ((System.currentTimeMillis() - params.lastUpdated) < 1.days.inWholeMilliseconds) {
            return item
        }
        return apiClient.placeById(
            item.id,
            language = params.lang,
        )?.toLocation()
    }

    override suspend fun search(query: LocationQuery, params: SearchParams): List<Location> {
        if (!params.allowNetwork) return emptyList()

        val results = apiClient.placesSearch(
            query.query,
            ll = FsqLatLon(
                latitude = query.userLatitude,
                longitude = query.userLongitude,
            ),
            radius = query.searchRadius.toInt(),
            language = if (params.lang in Languages) params.lang else "en"
        )
        Log.d("MM20", results.results?.joinToString(", ").toString())

        return results.results?.mapNotNull { it.toLocation() } ?: emptyList()
    }

    override suspend fun getPluginState(): PluginState {
        val context = context!!
        apiClient.apiKey.first() ?: return PluginState.SetupRequired(
            Intent(context, SettingsActivity::class.java),
            context.getString(R.string.plugin_state_setup_required)
        )
        return PluginState.Ready()
    }
}

private fun FsqPlace.toLocation(): Location? {
    return Location(
        id = fsqPlaceId ?: return null,
        label = name ?: return null,
        address = Address(
            address = location?.address,
            country = location?.country,
            state = location?.region,
            city = location?.locality,
            postalCode = location?.postcode,
        ),
        latitude = latitude ?: return null,
        longitude = longitude ?: return null,
        phoneNumber = tel,
        websiteUrl = website,
        userRating = rating?.div(10f),
        attribution = Attribution(
            text = "Foursquare",
            url = "https://foursquare.com/v/$fsqPlaceId",
            iconUrl = Uri.parse("android.resource://de.mm20.launcher2.plugin.foursquare/drawable/ic_foursquare"),
        ),
        icon = categories?.firstOrNull()?.toIcon(),
        category = categories?.firstOrNull()?.name,
        openingSchedule = hours?.regular?.let {
            OpeningSchedule.Hours(
                openingHours = it.mapNotNull { it.toOpeningHours() },
            )
        },
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
    return when (fsqCategoryId) {
        // Airport
        "5f2c42335b4c177b9a6dc927" -> LocationIcon.Airport // Airfield
        "4bf58dd8d48988d1ed931735" -> LocationIcon.Airport // Airport
        "4bf58dd8d48988d1ef931735" -> LocationIcon.Airport // Airport Food Court
        "4bf58dd8d48988d1f0931735" -> LocationIcon.Airport // Airport Gate
        "4eb1bc533b7b2c5b1d4306cb" -> LocationIcon.Airport // Airport Lounge
        "56aa371be4b08b9a8d57352f" -> LocationIcon.Airport // Airport Service
        "4bf58dd8d48988d1eb931735" -> LocationIcon.Airport // Airport Terminal
        "60a674555c7917283bad6839" -> LocationIcon.Airport // Airport Ticket Counter
        "5744ccdfe4b0c0459246b4e5" -> LocationIcon.Airport // Baggage Claim
        "5744ccdfe4b0c0459246b4e8" -> LocationIcon.Airport // Baggage Locker
        "56aa371ce4b08b9a8d57356e" -> LocationIcon.Airport // Heliport
        "63be6904847c3692a84b9c29" -> LocationIcon.Airport // International Airport
        "4bf58dd8d48988d1f7931735" -> LocationIcon.Airport // Plane
        "63be6904847c3692a84b9c2a" -> LocationIcon.Airport // Private Airport
        "4f04b25d2fb6e1c99f3db0c0" -> LocationIcon.Airport // Travel Lounge

        // AmericanFootball
        "4bf58dd8d48988d1b8941735" -> LocationIcon.AmericanFootball // College Football Field
        "63be6904847c3692a84b9c05" -> LocationIcon.AmericanFootball // Football
        "63be6904847c3692a84b9c06" -> LocationIcon.AmericanFootball // Football Club
        "63be6904847c3692a84b9c07" -> LocationIcon.AmericanFootball // Football Field

        // AmusementPark
        "4bf58dd8d48988d182941735" -> LocationIcon.AmusementPark // Amusement Park
        "4bf58dd8d48988d1e1931735" -> LocationIcon.AmusementPark // Arcade
        "63be6904847c3692a84b9b21" -> LocationIcon.AmusementPark // Carnival
        "63be6904847c3692a84b9bb7" -> LocationIcon.AmusementPark // Entertainment Event
        "5f2c2834b6d05514c704451e" -> LocationIcon.AmusementPark // Escape Room
        "4eb1daf44b900d56c88a4600" -> LocationIcon.AmusementPark // Fair
        "5267e4d9e4b0ec79466e48c7" -> LocationIcon.AmusementPark // Festival
        "5744ccdfe4b0c0459246b4b5" -> LocationIcon.AmusementPark // Indoor Play Area
        "63be6904847c3692a84b9b24" -> LocationIcon.AmusementPark // Party Center
        "4bf58dd8d48988d193941735" -> LocationIcon.AmusementPark // Water Park

        // ArtGallery
        "4bf58dd8d48988d1e2931735" -> LocationIcon.ArtGallery // Art Gallery
        "4bf58dd8d48988d18f941735" -> LocationIcon.ArtGallery // Art Museum
        "63be6904847c3692a84b9b28" -> LocationIcon.ArtGallery // Art Restoration Service
        "58daa1558bbb0b01f18ec1d6" -> LocationIcon.ArtGallery // Art Studio

        // AsianCuisine
        "52960eda3cf9994f4e043ac9" -> LocationIcon.AsianCuisine // Acehnese Restaurant
        "54135bf5e4b08f3d2429dfe5" -> LocationIcon.AsianCuisine // Andhra Restaurant
        "52af3a5e3cf9994f4e043bea" -> LocationIcon.AsianCuisine // Anhui Restaurant
        "4bf58dd8d48988d142941735" -> LocationIcon.AsianCuisine // Asian Restaurant
        "54135bf5e4b08f3d2429dff3" -> LocationIcon.AsianCuisine // Awadhi Restaurant
        "52960eda3cf9994f4e043acb" -> LocationIcon.AsianCuisine // Balinese Restaurant
        "52af3a723cf9994f4e043bec" -> LocationIcon.AsianCuisine // Beijing Restaurant
        "54135bf5e4b08f3d2429dff5" -> LocationIcon.AsianCuisine // Bengali Restaurant
        "52960eda3cf9994f4e043aca" -> LocationIcon.AsianCuisine // Betawinese Restaurant
        "56aa371be4b08b9a8d5734e4" -> LocationIcon.AsianCuisine // Bossam/Jokbal Restaurant
        "56aa371be4b08b9a8d5734f0" -> LocationIcon.AsianCuisine // Bunsik Restaurant
        "56aa371be4b08b9a8d573568" -> LocationIcon.AsianCuisine // Burmese Restaurant
        "52e81612bcbc57f1066b7a03" -> LocationIcon.AsianCuisine // Cambodian Restaurant
        "52af3a7c3cf9994f4e043bed" -> LocationIcon.AsianCuisine // Cantonese Restaurant
        "5293a7d53cf9994f4e043a45" -> LocationIcon.AsianCuisine // Caucasian Restaurant
        "54135bf5e4b08f3d2429dff2" -> LocationIcon.AsianCuisine // Chettinad Restaurant
        "52af3a673cf9994f4e043beb" -> LocationIcon.AsianCuisine // Chinese Aristocrat Restaurant
        "4bf58dd8d48988d145941735" -> LocationIcon.AsianCuisine // Chinese Restaurant
        "4bf58dd8d48988d1f5931735" -> LocationIcon.AsianCuisine // Dim Sum Restaurant
        "52af3a9f3cf9994f4e043bef" -> LocationIcon.AsianCuisine // Dongbei Restaurant
        "4eb1bd1c3b7b55596b4a748f" -> LocationIcon.AsianCuisine // Filipino Restaurant
        "52af3aaa3cf9994f4e043bf0" -> LocationIcon.AsianCuisine // Fujian Restaurant
        "54135bf5e4b08f3d2429dfe8" -> LocationIcon.AsianCuisine // Goan Restaurant
        "52af3ab53cf9994f4e043bf1" -> LocationIcon.AsianCuisine // Guizhou Restaurant
        "54135bf5e4b08f3d2429dfe9" -> LocationIcon.AsianCuisine // Gujarati Restaurant
        "56aa371be4b08b9a8d5734e7" -> LocationIcon.AsianCuisine // Gukbap Restaurant
        "52af3abe3cf9994f4e043bf2" -> LocationIcon.AsianCuisine // Hainan Restaurant
        "52af3ac83cf9994f4e043bf3" -> LocationIcon.AsianCuisine // Hakka Restaurant
        "52af3ad23cf9994f4e043bf4" -> LocationIcon.AsianCuisine // Henan Restaurant
        "52e81612bcbc57f1066b79fb" -> LocationIcon.AsianCuisine // Himalayan Restaurant
        "52af3add3cf9994f4e043bf5" -> LocationIcon.AsianCuisine // Hong Kong Restaurant
        "52af0bd33cf9994f4e043bdd" -> LocationIcon.AsianCuisine // Hotpot Restaurant
        "52af3af23cf9994f4e043bf7" -> LocationIcon.AsianCuisine // Huaiyang Restaurant
        "52af3ae63cf9994f4e043bf6" -> LocationIcon.AsianCuisine // Hubei Restaurant
        "52af3afc3cf9994f4e043bf8" -> LocationIcon.AsianCuisine // Hunan Restaurant
        "54135bf5e4b08f3d2429dfe6" -> LocationIcon.AsianCuisine // Hyderabadi Restaurant
        "52af3b053cf9994f4e043bf9" -> LocationIcon.AsianCuisine // Imperial Restaurant
        "54135bf5e4b08f3d2429dfdf" -> LocationIcon.AsianCuisine // Indian Chinese Restaurant
        "4bf58dd8d48988d10f941735" -> LocationIcon.AsianCuisine // Indian Restaurant
        "52960eda3cf9994f4e043acc" -> LocationIcon.AsianCuisine // Indonesian Meatball Restaurant
        "4deefc054765f83613cdba6f" -> LocationIcon.AsianCuisine // Indonesian Restaurant
        "54135bf5e4b08f3d2429dfea" -> LocationIcon.AsianCuisine // Jain Restaurant
        "56aa371be4b08b9a8d5734ed" -> LocationIcon.AsianCuisine // Janguh Restaurant
        "52960eda3cf9994f4e043ac7" -> LocationIcon.AsianCuisine // Javanese Restaurant
        "52af3b213cf9994f4e043bfa" -> LocationIcon.AsianCuisine // Jiangsu Restaurant
        "52af3b293cf9994f4e043bfb" -> LocationIcon.AsianCuisine // Jiangxi Restaurant
        "54135bf5e4b08f3d2429dfeb" -> LocationIcon.AsianCuisine // Karnataka Restaurant
        "54135bf5e4b08f3d2429dfed" -> LocationIcon.AsianCuisine // Kerala Restaurant
        "5f2c3f6b5b4c177b9a6dc388" -> LocationIcon.AsianCuisine // Korean BBQ Restaurant
        "4bf58dd8d48988d113941735" -> LocationIcon.AsianCuisine // Korean Restaurant
        "52af3b343cf9994f4e043bfc" -> LocationIcon.AsianCuisine // Macanese Restaurant
        "54135bf5e4b08f3d2429dfee" -> LocationIcon.AsianCuisine // Maharashtrian Restaurant
        "4bf58dd8d48988d156941735" -> LocationIcon.AsianCuisine // Malay Restaurant
        "5ae9595eb77c77002c2f9f26" -> LocationIcon.AsianCuisine // Mamak Restaurant
        "52960eda3cf9994f4e043ac8" -> LocationIcon.AsianCuisine // Manadonese Restaurant
        "52af3b3b3cf9994f4e043bfd" -> LocationIcon.AsianCuisine // Manchu Restaurant
        "4eb1d5724b900d56c88a45fe" -> LocationIcon.AsianCuisine // Mongolian Restaurant
        "54135bf5e4b08f3d2429dff4" -> LocationIcon.AsianCuisine // Mughlai Restaurant
        "54135bf5e4b08f3d2429dfe0" -> LocationIcon.AsianCuisine // Multicuisine Indian Restaurant
        "4bf58dd8d48988d1d1941735" -> LocationIcon.AsianCuisine // Noodle Restaurant
        "54135bf5e4b08f3d2429dfdd" -> LocationIcon.AsianCuisine // North Indian Restaurant
        "54135bf5e4b08f3d2429dff6" -> LocationIcon.AsianCuisine // Northeast Indian Restaurant
        "52960eda3cf9994f4e043ac5" -> LocationIcon.AsianCuisine // Padangnese Restaurant
        "54135bf5e4b08f3d2429dfef" -> LocationIcon.AsianCuisine // Parsi Restaurant
        "52af3b463cf9994f4e043bfe" -> LocationIcon.AsianCuisine // Peking Duck Restaurant
        "54135bf5e4b08f3d2429dff0" -> LocationIcon.AsianCuisine // Punjabi Restaurant
        "54135bf5e4b08f3d2429dff1" -> LocationIcon.AsianCuisine // Rajasthani Restaurant
        "56aa371be4b08b9a8d5734ea" -> LocationIcon.AsianCuisine // Samgyetang Restaurant
        "56aa371be4b08b9a8d57350e" -> LocationIcon.AsianCuisine // Satay Restaurant
        "52af3b633cf9994f4e043c01" -> LocationIcon.AsianCuisine // Shaanxi Restaurant
        "52af3b513cf9994f4e043bff" -> LocationIcon.AsianCuisine // Shandong Restaurant
        "52af3b593cf9994f4e043c00" -> LocationIcon.AsianCuisine // Shanghai Restaurant
        "52af3b6e3cf9994f4e043c02" -> LocationIcon.AsianCuisine // Shanxi Restaurant
        "5f2c430e5b4c177b9a6dcabd" -> LocationIcon.AsianCuisine // Singaporean Restaurant
        "56aa371be4b08b9a8d573502" -> LocationIcon.AsianCuisine // Som Tum Restaurant
        "54135bf5e4b08f3d2429dfde" -> LocationIcon.AsianCuisine // South Indian Restaurant
        "52960eda3cf9994f4e043ac6" -> LocationIcon.AsianCuisine // Sundanese Restaurant
        "52af3b773cf9994f4e043c03" -> LocationIcon.AsianCuisine // Szechuan Restaurant
        "52af3b813cf9994f4e043c04" -> LocationIcon.AsianCuisine // Taiwanese Restaurant
        "4bf58dd8d48988d149941735" -> LocationIcon.AsianCuisine // Thai Restaurant
        "52af3b893cf9994f4e043c05" -> LocationIcon.AsianCuisine // Tianjin Restaurant
        "52af39fb3cf9994f4e043be9" -> LocationIcon.AsianCuisine // Tibetan Restaurant
        "54135bf5e4b08f3d2429dfec" -> LocationIcon.AsianCuisine // Udupi Restaurant
        "4bf58dd8d48988d14a941735" -> LocationIcon.AsianCuisine // Vietnamese Restaurant
        "52af3b913cf9994f4e043c06" -> LocationIcon.AsianCuisine // Xinjiang Restaurant
        "52af3b9a3cf9994f4e043c07" -> LocationIcon.AsianCuisine // Yunnan Restaurant
        "52af3ba23cf9994f4e043c08" -> LocationIcon.AsianCuisine // Zhejiang Restaurant

        // Atm
        "52f2ab2ebcbc57f1066b8b56" -> LocationIcon.Atm // ATM

        // Bakery
        "4bf58dd8d48988d179941735" -> LocationIcon.Bakery // Bagel Shop
        "4bf58dd8d48988d16a941735" -> LocationIcon.Bakery // Bakery
        "4bf58dd8d48988d1bc941735" -> LocationIcon.Bakery // Cupcake Shop
        "4bf58dd8d48988d148941735" -> LocationIcon.Bakery // Donut Shop
        "5744ccdfe4b0c0459246b4e2" -> LocationIcon.Bakery // Pastry Shop
        "52e81612bcbc57f1066b7a0a" -> LocationIcon.Bakery // Pie Shop
        "62d5af45da6648532de303ee" -> LocationIcon.Bakery // Waffle Shop

        // Bank
        "4bf58dd8d48988d10a951735" -> LocationIcon.Bank // Bank
        "63be6904847c3692a84b9b3f" -> LocationIcon.Bank // Banking and Finance
        "52f2ab2ebcbc57f1066b8b2d" -> LocationIcon.Bank // Check Cashing Service
        "5032850891d4c4b30a586d62" -> LocationIcon.Bank // Credit Union
        "5744ccdfe4b0c0459246b4be" -> LocationIcon.Bank // Currency Exchange
        "63be6904847c3692a84b9b43" -> LocationIcon.Bank // Financial Planner
        "63be6904847c3692a84b9b3d" -> LocationIcon.Bank // Financial Service
        "503287a291d4c4b30a586d65" -> LocationIcon.Bank // Financial or Legal Service
        "63be6904847c3692a84b9b44" -> LocationIcon.Bank // Loans Agency
        "63be6904847c3692a84b9b45" -> LocationIcon.Bank // Stock Broker

        // Bar
        "4bf58dd8d48988d1ea941735" -> LocationIcon.Bar // Apres Ski Bar
        "4bf58dd8d48988d116941735" -> LocationIcon.Bar // Bar
        "52e81612bcbc57f1066b7a0d" -> LocationIcon.Bar // Beach Bar
        "56aa371ce4b08b9a8d57356c" -> LocationIcon.Bar // Beer Bar
        "62d587aeda6648532de2b88c" -> LocationIcon.Bar // Beer Festival
        "4bf58dd8d48988d117941735" -> LocationIcon.Bar // Beer Garden
        "50327c8591d4c4b30a586d5d" -> LocationIcon.Bar // Brewery
        "52e81612bcbc57f1066b7a0e" -> LocationIcon.Bar // Champagne Bar
        "5e189fd6eee47d000759bbfd" -> LocationIcon.Bar // Cidery
        "4bf58dd8d48988d11e941735" -> LocationIcon.Bar // Cocktail Bar
        "4e0e22f5a56208c4ea9a85a0" -> LocationIcon.Bar // Distillery
        "4bf58dd8d48988d118941735" -> LocationIcon.Bar // Dive Bar
        "4bf58dd8d48988d1d8941735" -> LocationIcon.Bar // Gay Bar
        "4bf58dd8d48988d119941735" -> LocationIcon.Bar // Hookah Bar
        "4bf58dd8d48988d1d5941735" -> LocationIcon.Bar // Hotel Bar
        "5f2c40f15b4c177b9a6dc684" -> LocationIcon.Bar // Ice Bar
        "4bf58dd8d48988d112941735" -> LocationIcon.Bar // Juice Bar
        "4bf58dd8d48988d120941735" -> LocationIcon.Bar // Karaoke Bar
        "55a5a1ebe4b013909087cbbf" -> LocationIcon.Bar // Lombard Restaurant
        "4bf58dd8d48988d121941735" -> LocationIcon.Bar // Lounge
        "5e189d71eee47d000759b7e2" -> LocationIcon.Bar // Meadery
        "4bf58dd8d48988d1e8931735" -> LocationIcon.Bar // Piano Bar
        "5f2c224bb6d05514c70440a3" -> LocationIcon.Bar // Rooftop Bar
        "4bf58dd8d48988d11c941735" -> LocationIcon.Bar // Sake Bar
        "4bf58dd8d48988d1d4941735" -> LocationIcon.Bar // Speakeasy
        "4bf58dd8d48988d11d941735" -> LocationIcon.Bar // Sports Bar
        "56aa371be4b08b9a8d57354d" -> LocationIcon.Bar // Tiki Bar
        "4bf58dd8d48988d1de941735" -> LocationIcon.Bar // Vineyard
        "4bf58dd8d48988d122941735" -> LocationIcon.Bar // Whisky Bar
        "4bf58dd8d48988d123941735" -> LocationIcon.Bar // Wine Bar
        "4bf58dd8d48988d14b941735" -> LocationIcon.Bar // Winery

        // Baseball
        "63be6904847c3692a84b9bfe" -> LocationIcon.Baseball // Baseball
        "63be6904847c3692a84b9bff" -> LocationIcon.Baseball // Baseball Club
        "4bf58dd8d48988d1e8941735" -> LocationIcon.Baseball // Baseball Field
        "63be6904847c3692a84b9bf5" -> LocationIcon.Baseball // Baseball Store
        "63be6904847c3692a84b9c00" -> LocationIcon.Baseball // Batting Cages
        "4bf58dd8d48988d1bb941735" -> LocationIcon.Baseball // College Baseball Diamond

        // Basketball
        "63be6904847c3692a84b9c01" -> LocationIcon.Basketball // Basketball
        "63be6904847c3692a84b9c02" -> LocationIcon.Basketball // Basketball Club
        "4bf58dd8d48988d1e1941735" -> LocationIcon.Basketball // Basketball Court
        "4bf58dd8d48988d1ba941735" -> LocationIcon.Basketball // College Basketball Court

        // Bike
        "4bf58dd8d48988d115951735" -> LocationIcon.Bike // Bicycle Store
        "4e4c9077bd41f78e849722f9" -> LocationIcon.Bike // Bike Rental
        "56aa371be4b08b9a8d57355e" -> LocationIcon.Bike // Bike Trail
        "52f2ab2ebcbc57f1066b8b49" -> LocationIcon.Bike // Cycle Studio

        // Boat
        "56aa371be4b08b9a8d573544" -> LocationIcon.Boat // Bay
        "5fabfc8099ce226e27fe6b0d" -> LocationIcon.Boat // Boat Launch
        "5744ccdfe4b0c0459246b4c1" -> LocationIcon.Boat // Boat Rental
        "4bf58dd8d48988d12d951735" -> LocationIcon.Boat // Boat or Ferry
        "52f2ab2ebcbc57f1066b8b20" -> LocationIcon.Boat // Body Piercing Shop
        "56aa371be4b08b9a8d573562" -> LocationIcon.Boat // Canal
        "56aa371be4b08b9a8d57353b" -> LocationIcon.Boat // Canal Lock
        "55077a22498e5e9248869ba2" -> LocationIcon.Boat // Cruise
        "4bf58dd8d48988d1e0941735" -> LocationIcon.Boat // Harbor or Marina
        "50aaa4314b90af0d42d5de10" -> LocationIcon.Boat // Island
        "4bf58dd8d48988d161941735" -> LocationIcon.Boat // Lake
        "5f2c1af1b6d05514c704319d" -> LocationIcon.Boat // Marine Terminal
        "4e74f6cabd41c4836eac4c31" -> LocationIcon.Boat // Pier
        "56aa371be4b08b9a8d57353e" -> LocationIcon.Boat // Port
        "56aa371be4b08b9a8d573541" -> LocationIcon.Boat // Reservoir
        "4eb1d4dd4b900d56c88a45fd" -> LocationIcon.Boat // River
        "63be6904847c3692a84b9c1f" -> LocationIcon.Boat // Sailing Club
        "56aa371be4b08b9a8d5734c3" -> LocationIcon.Boat // Waterfront

        // BookStore
        "4bf58dd8d48988d114951735" -> LocationIcon.BookStore // Bookstore
        "4bf58dd8d48988d1b1941735" -> LocationIcon.BookStore // College Bookstore
        "52f2ab2ebcbc57f1066b8b18" -> LocationIcon.BookStore // Comic Book Store
        "52f2ab2ebcbc57f1066b8b30" -> LocationIcon.BookStore // Used Bookstore

        // Breakfast
        "4bf58dd8d48988d1f8931735" -> LocationIcon.Breakfast // Bed and Breakfast
        "4bf58dd8d48988d143941735" -> LocationIcon.Breakfast // Breakfast Spot
        "52af3a903cf9994f4e043bee" -> LocationIcon.Breakfast // Chinese Breakfast Restaurant

        // BuddhistTemple
        "52e81612bcbc57f1066b7a3e" -> LocationIcon.BuddhistTemple // Buddhist Temple

        // Burger
        "4bf58dd8d48988d16c941735" -> LocationIcon.Burger // Burger Joint

        // Bus
        "4bf58dd8d48988d12b951735" -> LocationIcon.Bus // Bus Line
        "4bf58dd8d48988d1fe931735" -> LocationIcon.Bus // Bus Station
        "52f2ab2ebcbc57f1066b8b4f" -> LocationIcon.Bus // Bus Stop
        "63be6904847c3692a84b9c2b" -> LocationIcon.Bus // Charter Bus

        // CableCar
        "52f2ab2ebcbc57f1066b8b50" -> LocationIcon.CableCar // Cable Car

        // Cafe
        "52e81612bcbc57f1066b7a0c" -> LocationIcon.Cafe // Bubble Tea Shop
        "63be6904847c3692a84b9bb6" -> LocationIcon.Cafe // Cafe, Coffee, and Tea House
        "4bf58dd8d48988d128941735" -> LocationIcon.Cafe // Cafeteria
        "4bf58dd8d48988d16d941735" -> LocationIcon.Cafe // Café
        "5e18993feee47d000759b256" -> LocationIcon.Cafe // Coffee Roaster
        "4bf58dd8d48988d1e0931735" -> LocationIcon.Cafe // Coffee Shop
        "54f4ba06498e2cf5561da814" -> LocationIcon.Cafe // Corporate Cafeteria
        "5665c7b9498e7d8a4f2c0f06" -> LocationIcon.Cafe // Corporate Coffee Shop
        "4bf58dd8d48988d18d941735" -> LocationIcon.Cafe // Gaming Cafe
        "4bf58dd8d48988d1f0941735" -> LocationIcon.Cafe // Internet Cafe
        "56aa371be4b08b9a8d573508" -> LocationIcon.Cafe // Pet Café
        "52f2ab2ebcbc57f1066b8b41" -> LocationIcon.Cafe // Smoothie Shop
        "4bf58dd8d48988d1dc931735" -> LocationIcon.Cafe // Tea Room
        "56aa371be4b08b9a8d5734c1" -> LocationIcon.Cafe // Turkish Coffeehouse
        "5f2c14a5b6d05514c7042eb7" -> LocationIcon.Cafe // VR Cafe

        // Car
        "63be6904847c3692a84b9be3" -> LocationIcon.Car // Automotive Retail
        "4eb1c1623b7b52c0e1adc2ec" -> LocationIcon.Car // Car Dealership
        "63be6904847c3692a84b9be4" -> LocationIcon.Car // Classic and Antique Car Dealership
        "52f2ab2ebcbc57f1066b8b4c" -> LocationIcon.Car // Intersection
        "63be6904847c3692a84b9c2c" -> LocationIcon.Car // Limo Service
        "5e8f50bd03c7a9000c1e2fbc" -> LocationIcon.Car // New Car Dealership
        "63be6904847c3692a84b9be5" -> LocationIcon.Car // RV and Motorhome Dealership
        "4bf58dd8d48988d1f9931735" -> LocationIcon.Car // Road
        "52f2ab2ebcbc57f1066b8b52" -> LocationIcon.Car // Street
        "52f2ab2ebcbc57f1066b8b4d" -> LocationIcon.Car // Toll Booth
        "52f2ab2ebcbc57f1066b8b4e" -> LocationIcon.Car // Toll Plaza
        "63be6904847c3692a84b9b30" -> LocationIcon.Car // Towing Service
        "5e8f501a03c7a9000c1e2e88" -> LocationIcon.Car // Used Car Dealership

        // CarRental
        "4bf58dd8d48988d1ef941735" -> LocationIcon.CarRental // Rental Car Location
        "56aa371be4b08b9a8d573552" -> LocationIcon.CarRental // Rental Service

        // CarRepair
        "56aa371be4b08b9a8d5734d3" -> LocationIcon.CarRepair // Auto Workshop
        "52f2ab2ebcbc57f1066b8b44" -> LocationIcon.CarRepair // Automotive Repair Shop
        "63be6904847c3692a84b9b2b" -> LocationIcon.CarRepair // Automotive Service
        "4bf58dd8d48988d124951735" -> LocationIcon.CarRepair // Automotive Shop
        "63be6904847c3692a84b9be6" -> LocationIcon.CarRepair // Car Parts and Accessories
        "63be6904847c3692a84b9b2c" -> LocationIcon.CarRepair // Motorcycle Repair Shop
        "63be6904847c3692a84b9b2d" -> LocationIcon.CarRepair // Oil Change Service
        "52f2ab2ebcbc57f1066b8b2f" -> LocationIcon.CarRepair // Repair Service
        "63be6904847c3692a84b9b2e" -> LocationIcon.CarRepair // Smog Check Shop
        "63be6904847c3692a84b9b2f" -> LocationIcon.CarRepair // Tire Repair Shop
        "63be6904847c3692a84b9b31" -> LocationIcon.CarRepair // Transmissions Shop
        "5f2c1e0db6d05514c70436d4" -> LocationIcon.CarRepair // Vehicle Inspection Station

        // CarWash
        "4f04ae1f2fb6e1c99f3db0ba" -> LocationIcon.CarWash // Car Wash and Detail

        // Casino
        "52f2ab2ebcbc57f1066b8b40" -> LocationIcon.Casino // Betting Shop
        "63be6904847c3692a84b9b20" -> LocationIcon.Casino // Bingo Center
        "4bf58dd8d48988d17c941735" -> LocationIcon.Casino // Casino
        "5744ccdfe4b0c0459246b4b8" -> LocationIcon.Casino // Pachinko Parlor

        // CellPhoneStore
        "63be6904847c3692a84b9bea" -> LocationIcon.CellPhoneStore // Computers and Electronics Retail
        "4bf58dd8d48988d122951735" -> LocationIcon.CellPhoneStore // Electronics Store
        "63be6904847c3692a84b9b74" -> LocationIcon.CellPhoneStore // Mobile Company
        "4f04afc02fb6e1c99f3db0bc" -> LocationIcon.CellPhoneStore // Mobile Phone Store
        "63be6904847c3692a84b9b93" -> LocationIcon.CellPhoneStore // Telecommunication Service

        // ChargingStation
        "5032872391d4c4b30a586d64" -> LocationIcon.ChargingStation // Electric Vehicle Charging Station
        "63be6904847c3692a84b9b8a" -> LocationIcon.ChargingStation // Renewable Energy Service

        // Church
        "4bf58dd8d48988d132941735" -> LocationIcon.Church // Church

        // Circus
        "52e81612bcbc57f1066b79e7" -> LocationIcon.Circus // Circus
        "52e81612bcbc57f1066b7a43" -> LocationIcon.Circus // Circus School

        // Clinic
        "52e81612bcbc57f1066b7a3b" -> LocationIcon.Clinic // Acupuncture Clinic
        "52e81612bcbc57f1066b7a3c" -> LocationIcon.Clinic // Alternative Medicine Clinic
        "52e81612bcbc57f1066b7a3a" -> LocationIcon.Clinic // Chiropractor
        "63be6904847c3692a84b9bbe" -> LocationIcon.Clinic // Healthcare Clinic
        "52f2ab2ebcbc57f1066b8b3c" -> LocationIcon.Clinic // Massage Clinic
        "56aa371be4b08b9a8d5734ff" -> LocationIcon.Clinic // Maternity Clinic
        "52e81612bcbc57f1066b7a39" -> LocationIcon.Clinic // Mental Health Clinic
        "5744ccdfe4b0c0459246b4af" -> LocationIcon.Clinic // Physical Therapy Clinic
        "63be6904847c3692a84b9bde" -> LocationIcon.Clinic // Sports Medicine Clinic
        "63be6904847c3692a84b9bdf" -> LocationIcon.Clinic // Women's Health Clinic

        // ClothingStore
        "56aa371be4b08b9a8d5734cb" -> LocationIcon.ClothingStore // Batik Store
        "4bf58dd8d48988d104951735" -> LocationIcon.ClothingStore // Boutique
        "4bf58dd8d48988d11a951735" -> LocationIcon.ClothingStore // Bridal Store
        "4bf58dd8d48988d105951735" -> LocationIcon.ClothingStore // Children's Clothing Store
        "4bf58dd8d48988d103951735" -> LocationIcon.ClothingStore // Clothing Store
        "52f2ab2ebcbc57f1066b8b17" -> LocationIcon.ClothingStore // Costume Store
        "63be6904847c3692a84b9beb" -> LocationIcon.ClothingStore // Dance Store
        "4bf58dd8d48988d102951735" -> LocationIcon.ClothingStore // Fashion Accessories Store
        "63be6904847c3692a84b9bec" -> LocationIcon.ClothingStore // Fashion Retail
        "4bf58dd8d48988d111951735" -> LocationIcon.ClothingStore // Jewelry Store
        "4bf58dd8d48988d109951735" -> LocationIcon.ClothingStore // Lingerie Store
        "4bf58dd8d48988d106951735" -> LocationIcon.ClothingStore // Men's Store
        "4bf58dd8d48988d107951735" -> LocationIcon.ClothingStore // Shoe Store
        "63be6904847c3692a84b9bed" -> LocationIcon.ClothingStore // Sunglasses Store
        "63be6904847c3692a84b9bee" -> LocationIcon.ClothingStore // Swimwear Store
        "5032781d91d4c4b30a586d5b" -> LocationIcon.ClothingStore // Tailor
        "52f2ab2ebcbc57f1066b8b26" -> LocationIcon.ClothingStore // Textiles Store
        "4bf58dd8d48988d101951735" -> LocationIcon.ClothingStore // Vintage and Thrift Store
        "52f2ab2ebcbc57f1066b8b2e" -> LocationIcon.ClothingStore // Watch Store
        "4bf58dd8d48988d108951735" -> LocationIcon.ClothingStore // Women's Store

        // ConcertHall
        "5032792091d4c4b30a586d5c" -> LocationIcon.ConcertHall // Concert Hall
        "4bf58dd8d48988d1e7931735" -> LocationIcon.ConcertHall // Jazz and Blues Venue
        "5267e4d9e4b0ec79466e48d1" -> LocationIcon.ConcertHall // Music Festival
        "4bf58dd8d48988d1e5931735" -> LocationIcon.ConcertHall // Music Venue
        "4bf58dd8d48988d1e9931735" -> LocationIcon.ConcertHall // Rock Club

        // ConvenienceStore
        "4d954b0ea243a5684a65b473" -> LocationIcon.ConvenienceStore // Convenience Store

        // Courthouse
        "4bf58dd8d48988d12b941735" -> LocationIcon.Courthouse // Courthouse
        "63be6904847c3692a84b9b6c" -> LocationIcon.Courthouse // Immigration Attorney
        "52f2ab2ebcbc57f1066b8b3f" -> LocationIcon.Courthouse // Law Office
        "63be6904847c3692a84b9b6b" -> LocationIcon.Courthouse // Legal Service
        "5ae95d208a6f17002ce792b2" -> LocationIcon.Courthouse // Notary

        // Cricket
        "4bf58dd8d48988d1b9941735" -> LocationIcon.Cricket // College Cricket Pitch
        "4bf58dd8d48988d18a941735" -> LocationIcon.Cricket // Cricket Ground

        // Dentist
        "4bf58dd8d48988d178941735" -> LocationIcon.Dentist // Dentist
        "63be6904847c3692a84b9bd4" -> LocationIcon.Dentist // Oral Surgeon

        // DiscountStore
        "52dea92d3cf9994f4e043dbb" -> LocationIcon.DiscountStore // Discount Store

        // ElectricScooter
        "63be6904847c3692a84b9be8" -> LocationIcon.ElectricScooter // Motor Scooter Dealership

        // FastFood
        "4bf58dd8d48988d16e941735" -> LocationIcon.FastFood // Fast Food Restaurant
        "4edd64a0c7ddd24ca188df1a" -> LocationIcon.FastFood // Fish and Chips Shop
        "56aa371be4b08b9a8d57350b" -> LocationIcon.FastFood // Food Stand
        "4bf58dd8d48988d1cb941735" -> LocationIcon.FastFood // Food Truck
        "4d4ae6fc7a7b7dea34424761" -> LocationIcon.FastFood // Fried Chicken Joint
        "55d25775498e9f6a0816a37a" -> LocationIcon.FastFood // Friterie
        "4bf58dd8d48988d16f941735" -> LocationIcon.FastFood // Hot Dog Joint
        "4bf58dd8d48988d1c7941735" -> LocationIcon.FastFood // Snack Place
        "4bf58dd8d48988d14c941735" -> LocationIcon.FastFood // Wings Joint

        // FireDepartment
        "4bf58dd8d48988d12c941735" -> LocationIcon.FireDepartment // Fire Station

        // FitnessCenter
        "4bf58dd8d48988d176941735" -> LocationIcon.FitnessCenter // Gym
        "4bf58dd8d48988d175941735" -> LocationIcon.FitnessCenter // Gym and Studio
        "58daa1558bbb0b01f18ec203" -> LocationIcon.FitnessCenter // Outdoor Gym
        "63be6904847c3692a84b9c0e" -> LocationIcon.FitnessCenter // Personal Trainer
        "5744ccdfe4b0c0459246b4b2" -> LocationIcon.FitnessCenter // Pilates Studio
        "590a0744340a5803fd8508c3" -> LocationIcon.FitnessCenter // Weight Loss Center
        "4bf58dd8d48988d102941735" -> LocationIcon.FitnessCenter // Yoga Studio

        // Florist
        "4bf58dd8d48988d11b951735" -> LocationIcon.Florist // Flower Store

        // Forest
        "63be6904847c3692a84b9b26" -> LocationIcon.Forest // Agriculture and Forestry Service
        "52e81612bcbc57f1066b7a23" -> LocationIcon.Forest // Forest
        "63be6904847c3692a84b9b6d" -> LocationIcon.Forest // Logging Service
        "52e81612bcbc57f1066b7a13" -> LocationIcon.Forest // Nature Preserve
        "52e81612bcbc57f1066b7a24" -> LocationIcon.Forest // Tree
        "63be6904847c3692a84b9b64" -> LocationIcon.Forest // Tree Service

        // FurnitureStore
        "4bf58dd8d48988d1f8941735" -> LocationIcon.FurnitureStore // Furniture and Home Store
        "63be6904847c3692a84b9bf1" -> LocationIcon.FurnitureStore // Home Appliance Store
        "63be6904847c3692a84b9bf2" -> LocationIcon.FurnitureStore // Housewares Store
        "58daa1558bbb0b01f18ec1b4" -> LocationIcon.FurnitureStore // Kitchen Supply Store
        "55888a5a498e782e3303b43a" -> LocationIcon.FurnitureStore // Lighting Store
        "52f2ab2ebcbc57f1066b8b27" -> LocationIcon.FurnitureStore // Mattress Store

        // GasStation
        "4bf58dd8d48988d113951735" -> LocationIcon.GasStation // Fuel Station
        "63be6904847c3692a84b9b7b" -> LocationIcon.GasStation // Petroleum Supplier

        // GenericTransit
        "52f2ab2ebcbc57f1066b8b4b" -> LocationIcon.GenericTransit // Border Crossing
        "4bf58dd8d48988d1f6931735" -> LocationIcon.GenericTransit // General Travel
        "4f2a23984b9023bd5841ed2c" -> LocationIcon.GenericTransit // Moving Target
        "63be6904847c3692a84b9c2d" -> LocationIcon.GenericTransit // Public Transportation
        "52f2ab2ebcbc57f1066b8b1f" -> LocationIcon.GenericTransit // Shipping, Freight, and Material Transportation Service
        "63be6904847c3692a84b9c28" -> LocationIcon.GenericTransit // Transport Hub
        "54541b70498ea6ccd0204bff" -> LocationIcon.GenericTransit // Transportation Service
        "4d4b7105d754a06379d81259" -> LocationIcon.GenericTransit // Travel and Transportation
        "52f2ab2ebcbc57f1066b8b4a" -> LocationIcon.GenericTransit // Tunnel

        // Golf
        "52e81612bcbc57f1066b79e8" -> LocationIcon.Golf // Disc Golf
        "63be6904847c3692a84b9c03" -> LocationIcon.Golf // Disc Golf Course
        "63be6904847c3692a84b9c08" -> LocationIcon.Golf // Golf
        "63be6904847c3692a84b9c09" -> LocationIcon.Golf // Golf Club
        "4bf58dd8d48988d1e6941735" -> LocationIcon.Golf // Golf Course
        "58daa1558bbb0b01f18ec1b0" -> LocationIcon.Golf // Golf Driving Range
        "63be6904847c3692a84b9bf6" -> LocationIcon.Golf // Golf Store
        "52e81612bcbc57f1066b79eb" -> LocationIcon.Golf // Mini Golf Course

        // GovernmentBuilding
        "4bf58dd8d48988d12a941735" -> LocationIcon.GovernmentBuilding // Capitol Building
        "4bf58dd8d48988d129941735" -> LocationIcon.GovernmentBuilding // City Hall
        "4bf58dd8d48988d126941735" -> LocationIcon.GovernmentBuilding // Government Building
        "63be6904847c3692a84b9ba7" -> LocationIcon.GovernmentBuilding // Government Lobbyist
        "4cae28ecbf23941eb1190695" -> LocationIcon.GovernmentBuilding // Polling Place
        "52e81612bcbc57f1066b7a38" -> LocationIcon.GovernmentBuilding // Town Hall

        // Gymnastics
        "63be6904847c3692a84b9c0a" -> LocationIcon.Gymnastics // Gymnastics
        "52f2ab2ebcbc57f1066b8b48" -> LocationIcon.Gymnastics // Gymnastics Center

        // HairSalon
        "63be6904847c3692a84b9b49" -> LocationIcon.HairSalon // Barbershop
        "63be6904847c3692a84b9b4a" -> LocationIcon.HairSalon // Hair Removal Service
        "4bf58dd8d48988d110951735" -> LocationIcon.HairSalon // Hair Salon
        "54541900498ea6ccd0202697" -> LocationIcon.HairSalon // Health and Beauty Service
        "4f04aa0c2fb6e1c99f3db0b8" -> LocationIcon.HairSalon // Nail Salon
        "4d1cf8421a97d635ce361c31" -> LocationIcon.HairSalon // Tanning Salon

        // Hiking
        "56aa371be4b08b9a8d573511" -> LocationIcon.Hiking // Cave
        "4bf58dd8d48988d159941735" -> LocationIcon.Hiking // Hiking Trail
        "5bae9231bedf3950379f89cd" -> LocationIcon.Hiking // Hill
        "4eb1d4d54b900d56c88a45fc" -> LocationIcon.Hiking // Mountain
        "55a5a1ebe4b013909087cb77" -> LocationIcon.Hiking // Mountain Hut
        "50328a4b91d4c4b30a586d6b" -> LocationIcon.Hiking // Rock Climbing Spot
        "4eb1c0f63b7b52c0e1adc2eb" -> LocationIcon.Hiking // Ski Trail
        "52f2ab2ebcbc57f1066b8b55" -> LocationIcon.Hiking // Trailer Park
        "5032848691d4c4b30a586d61" -> LocationIcon.Hiking // Volcano
        "56aa371be4b08b9a8d573560" -> LocationIcon.Hiking // Waterfall

        // HinduTemple
        "52e81612bcbc57f1066b7a3f" -> LocationIcon.HinduTemple // Hindu Temple

        // Hockey
        "4bf58dd8d48988d1b5941735" -> LocationIcon.Hockey // College Hockey Rink
        "56aa371be4b08b9a8d57351a" -> LocationIcon.Hockey // Curling Ice
        "63be6904847c3692a84b9c0b" -> LocationIcon.Hockey // Hockey
        "63be6904847c3692a84b9c0c" -> LocationIcon.Hockey // Hockey Club
        "4f452cd44b9081a197eba860" -> LocationIcon.Hockey // Hockey Field
        "56aa371be4b08b9a8d57352c" -> LocationIcon.Hockey // Hockey Rink

        // Hospital
        "63be6904847c3692a84b9bba" -> LocationIcon.Hospital // AIDS Resource
        "63be6904847c3692a84b9b9b" -> LocationIcon.Hospital // Addiction Treatment Center
        "63be6904847c3692a84b9bbd" -> LocationIcon.Hospital // Ambulance Service
        "5032891291d4c4b30a586d68" -> LocationIcon.Hospital // Assisted Living
        "63be6904847c3692a84b9bbb" -> LocationIcon.Hospital // Assisted Living Service
        "5f2c43a65b4c177b9a6dcc62" -> LocationIcon.Hospital // Blood Bank
        "63be6904847c3692a84b9bc0" -> LocationIcon.Hospital // Children's Hospital
        "63be6904847c3692a84b9b9c" -> LocationIcon.Hospital // Disabled Persons Service
        "63be6904847c3692a84b9b9d" -> LocationIcon.Hospital // Domestic Abuse Treatment Center
        "4bf58dd8d48988d194941735" -> LocationIcon.Hospital // Emergency Room
        "63be6904847c3692a84b9bbc" -> LocationIcon.Hospital // Emergency Service
        "63be6904847c3692a84b9bb9" -> LocationIcon.Hospital // Health and Medicine
        "63be6904847c3692a84b9bbf" -> LocationIcon.Hospital // Home Health Care Service
        "5f2c5b8b5b4c177b9a6ddf0b" -> LocationIcon.Hospital // Hospice
        "4bf58dd8d48988d196941735" -> LocationIcon.Hospital // Hospital
        "58daa1558bbb0b01f18ec1f7" -> LocationIcon.Hospital // Hospital Unit
        "63be6904847c3692a84b9b69" -> LocationIcon.Hospital // Laboratory
        "63be6904847c3692a84b9bc1" -> LocationIcon.Hospital // Mental Health Service
        "63be6904847c3692a84b9bc4" -> LocationIcon.Hospital // Nursing Home
        "56aa371be4b08b9a8d57351d" -> LocationIcon.Hospital // Rehabilitation Center
        "5744ccdfe4b0c0459246b4d6" -> LocationIcon.Hospital // Research Laboratory
        "63be6904847c3692a84b9bb2" -> LocationIcon.Hospital // Retirement Home
        "63be6904847c3692a84b9bb3" -> LocationIcon.Hospital // Senior Citizen Service
        "56aa371be4b08b9a8d573526" -> LocationIcon.Hospital // Urgent Care Center

        // Hotel
        "52e81612bcbc57f1066b7a27" -> LocationIcon.Hotel // Bath House
        "4f4530a74b9074f6e4fb0100" -> LocationIcon.Hotel // Boarding House
        "63be6904847c3692a84b9c26" -> LocationIcon.Hotel // Cabin
        "4bf58dd8d48988d1ee931735" -> LocationIcon.Hotel // Hostel
        "4bf58dd8d48988d1fa931735" -> LocationIcon.Hotel // Hotel
        "5bae9231bedf3950379f89cb" -> LocationIcon.Hotel // Inn
        "63be6904847c3692a84b9c27" -> LocationIcon.Hotel // Lodge
        "63be6904847c3692a84b9c25" -> LocationIcon.Hotel // Lodging
        "4bf58dd8d48988d1fb931735" -> LocationIcon.Hotel // Motel
        "4bf58dd8d48988d12f951735" -> LocationIcon.Hotel // Resort
        "58daa1558bbb0b01f18ec1ae" -> LocationIcon.Hotel // Sauna
        "4bf58dd8d48988d1ed941735" -> LocationIcon.Hotel // Spa
        "56aa371be4b08b9a8d5734e1" -> LocationIcon.Hotel // Vacation Rental
        "56aa371be4b08b9a8d5734c5" -> LocationIcon.Hotel // Wedding Hall

        // IceCream
        "4bf58dd8d48988d1d0941735" -> LocationIcon.IceCream // Dessert Shop
        "512e7cae91d4cbb4e5efe0af" -> LocationIcon.IceCream // Frozen Yogurt Shop
        "5f2c407c5b4c177b9a6dc536" -> LocationIcon.IceCream // Gelato Shop
        "4bf58dd8d48988d1c9941735" -> LocationIcon.IceCream // Ice Cream Parlor

        // JapaneseCuisine
        "55a59bace4b013909087cb0c" -> LocationIcon.JapaneseCuisine // Donburi Restaurant
        "55a59bace4b013909087cb30" -> LocationIcon.JapaneseCuisine // Japanese Curry Restaurant
        "5f2c2436b6d05514c704433e" -> LocationIcon.JapaneseCuisine // Japanese Family Restaurant
        "4bf58dd8d48988d111941735" -> LocationIcon.JapaneseCuisine // Japanese Restaurant
        "55a59bace4b013909087cb21" -> LocationIcon.JapaneseCuisine // Kaiseki Restaurant
        "55a59bace4b013909087cb06" -> LocationIcon.JapaneseCuisine // Kushikatsu Restaurant
        "55a59bace4b013909087cb1b" -> LocationIcon.JapaneseCuisine // Monjayaki Restaurant
        "55a59bace4b013909087cb1e" -> LocationIcon.JapaneseCuisine // Nabe Restaurant
        "55a59bace4b013909087cb18" -> LocationIcon.JapaneseCuisine // Okonomiyaki Restaurant
        "55a59bace4b013909087cb15" -> LocationIcon.JapaneseCuisine // Shabu-Shabu Restaurant
        "55a59bace4b013909087cb27" -> LocationIcon.JapaneseCuisine // Soba Restaurant
        "55a59bace4b013909087cb12" -> LocationIcon.JapaneseCuisine // Sukiyaki Restaurant
        "4bf58dd8d48988d1d2941735" -> LocationIcon.JapaneseCuisine // Sushi Restaurant
        "5f2c239eb6d05514c70441ee" -> LocationIcon.JapaneseCuisine // Teishoku Restaurant
        "55a59a31e4b013909087cb00" -> LocationIcon.JapaneseCuisine // Tempura Restaurant
        "55a59af1e4b013909087cb03" -> LocationIcon.JapaneseCuisine // Tonkatsu Restaurant
        "55a59bace4b013909087cb2a" -> LocationIcon.JapaneseCuisine // Udon Restaurant
        "55a59bace4b013909087cb0f" -> LocationIcon.JapaneseCuisine // Unagi Restaurant
        "55a59bace4b013909087cb09" -> LocationIcon.JapaneseCuisine // Yakitori Restaurant
        "55a59bace4b013909087cb36" -> LocationIcon.JapaneseCuisine // Yoshoku Restaurant

        // Kayaking
        "63be6904847c3692a84b9c1d" -> LocationIcon.Kayaking // Canoe and Kayak Rental
        "63be6904847c3692a84b9c1e" -> LocationIcon.Kayaking // Rafting Outfitter
        "52e81612bcbc57f1066b7a29" -> LocationIcon.Kayaking // Rafting Spot

        // Kebab
        "5283c7b4e4b094cb91ec88d8" -> LocationIcon.Kebab // Doner Restaurant
        "5283c7b4e4b094cb91ec88d7" -> LocationIcon.Kebab // Kebab Restaurant
        "5bae9231bedf3950379f89e4" -> LocationIcon.Kebab // Shawarma Restaurant
        "52e81612bcbc57f1066b79f3" -> LocationIcon.Kebab // Souvlaki Shop

        // Kiosk
        "52f2ab2ebcbc57f1066b8b38" -> LocationIcon.Kiosk // Lottery Retailer
        "5f2c5a295b4c177b9a6ddd0e" -> LocationIcon.Kiosk // Newsagent
        "4f04ad622fb6e1c99f3db0b9" -> LocationIcon.Kiosk // Newsstand

        // Laundromat
        "52f2ab2ebcbc57f1066b8b1d" -> LocationIcon.Laundromat // Dry Cleaner
        "52f2ab2ebcbc57f1066b8b33" -> LocationIcon.Laundromat // Laundromat
        "4bf58dd8d48988d1fc941735" -> LocationIcon.Laundromat // Laundry Service
        "63be6904847c3692a84b9b60" -> LocationIcon.Laundromat // Professional Cleaning Service

        // Library
        "4bf58dd8d48988d12f941735" -> LocationIcon.Library // Library

        // LiquorStore
        "5370f356bcbc57f1066c94c2" -> LocationIcon.LiquorStore // Beer Store
        "4bf58dd8d48988d186941735" -> LocationIcon.LiquorStore // Liquor Store
        "4bf58dd8d48988d119951735" -> LocationIcon.LiquorStore // Wine Store

        // MartialArts
        "52f2ab2ebcbc57f1066b8b47" -> LocationIcon.MartialArts // Boxing Gym
        "4bf58dd8d48988d101941735" -> LocationIcon.MartialArts // Martial Arts Dojo

        // Monument
        "4bf58dd8d48988d1df941735" -> LocationIcon.Monument // Bridge
        "50aaa49e4b90af0d42d5de11" -> LocationIcon.Monument // Castle
        "4bf58dd8d48988d15c941735" -> LocationIcon.Monument // Cemetery
        "5fac018b99ce226e27fe7573" -> LocationIcon.Monument // Dam
        "56aa371be4b08b9a8d573547" -> LocationIcon.Monument // Fountain
        "4deefb944765f83613cdba6e" -> LocationIcon.Monument // Historic and Protected Site
        "4bf58dd8d48988d15d941735" -> LocationIcon.Monument // Lighthouse
        "5642206c498e4bfca532186c" -> LocationIcon.Monument // Memorial Site
        "4bf58dd8d48988d12d941735" -> LocationIcon.Monument // Monument
        "52e81612bcbc57f1066b79ed" -> LocationIcon.Monument // Outdoor Sculpture
        "52e81612bcbc57f1066b7a14" -> LocationIcon.Monument // Palace
        "52741d85e4b0d5d1e3c6a6d9" -> LocationIcon.Monument // Parade
        "52e81612bcbc57f1066b7a25" -> LocationIcon.Monument // Pedestrian Plaza
        "4bf58dd8d48988d164941735" -> LocationIcon.Monument // Plaza
        "507c8c4091d498d9fc8c67a9" -> LocationIcon.Monument // Public Art
        "4bf58dd8d48988d165941735" -> LocationIcon.Monument // Scenic Lookout
        "4bf58dd8d48988d166941735" -> LocationIcon.Monument // Sculpture Garden
        "52e81612bcbc57f1066b79ee" -> LocationIcon.Monument // Street Art
        "4bf58dd8d48988d130941735" -> LocationIcon.Monument // Structure
        "5bae9231bedf3950379f89c7" -> LocationIcon.Monument // Windmill

        // Moped
        "63be6904847c3692a84b9be7" -> LocationIcon.Moped // Moped Dealership

        // Mosque
        "4bf58dd8d48988d138941735" -> LocationIcon.Mosque // Mosque

        // Motorcycle
        "5032833091d4c4b30a586d60" -> LocationIcon.Motorcycle // Motorcycle Dealership

        // Motorsports
        "52e81612bcbc57f1066b79ea" -> LocationIcon.Motorsports // Go Kart Track
        "59d79d6b2e268052fa2a3332" -> LocationIcon.Motorsports // Motorsports Store
        "4bf58dd8d48988d1f4931735" -> LocationIcon.Motorsports // Race Track
        "56aa371be4b08b9a8d573514" -> LocationIcon.Motorsports // Racecourse

        // MovieTheater
        "56aa371be4b08b9a8d5734de" -> LocationIcon.MovieTheater // Drive-in Theater
        "56aa371be4b08b9a8d573523" -> LocationIcon.MovieTheater // Film Studio
        "4bf58dd8d48988d17e941735" -> LocationIcon.MovieTheater // Indie Movie Theater
        "4bf58dd8d48988d17f941735" -> LocationIcon.MovieTheater // Movie Theater
        "4bf58dd8d48988d180941735" -> LocationIcon.MovieTheater // Multiplex

        // Museum
        "4fceea171983d5d06c3e9823" -> LocationIcon.Museum // Aquarium
        "52e81612bcbc57f1066b7a32" -> LocationIcon.Museum // Cultural Center
        "559acbe0498e472f1a53fa23" -> LocationIcon.Museum // Erotic Museum
        "56aa371be4b08b9a8d573532" -> LocationIcon.Museum // Exhibit
        "4bf58dd8d48988d190941735" -> LocationIcon.Museum // History Museum
        "4bf58dd8d48988d181941735" -> LocationIcon.Museum // Museum
        "5744ccdfe4b0c0459246b4d9" -> LocationIcon.Museum // Observatory
        "4bf58dd8d48988d192941735" -> LocationIcon.Museum // Planetarium
        "4bf58dd8d48988d191941735" -> LocationIcon.Museum // Science Museum
        "58daa1558bbb0b01f18ec1fd" -> LocationIcon.Museum // Zoo Exhibit

        // NightClub
        "4bf58dd8d48988d18e941735" -> LocationIcon.NightClub // Comedy Club
        "52e81612bcbc57f1066b79ef" -> LocationIcon.NightClub // Country Dance Club
        "63be6904847c3692a84b9b23" -> LocationIcon.NightClub // Dance Hall
        "5744ccdfe4b0c0459246b4bb" -> LocationIcon.NightClub // Karaoke Box
        "4bf58dd8d48988d11f941735" -> LocationIcon.NightClub // Night Club
        "4d4b7105d754a06376d81259" -> LocationIcon.NightClub // Nightlife Spot
        "4bf58dd8d48988d11a941735" -> LocationIcon.NightClub // Other Nightlife
        "52e81612bcbc57f1066b79ec" -> LocationIcon.NightClub // Salsa Club
        "4bf58dd8d48988d1d6941735" -> LocationIcon.NightClub // Strip Club

        // Optician
        "4d954afda243a5684865b473" -> LocationIcon.Optician // Eyecare Store
        "522e32fae4b09b556e370f19" -> LocationIcon.Optician // Optometrist

        // Paragliding
        "63be6904847c3692a84b9c24" -> LocationIcon.Paragliding // Hot Air Balloon Tour Agency
        "63be6904847c3692a84b9c18" -> LocationIcon.Paragliding // Skydiving Center
        "58daa1558bbb0b01f18ec1b9" -> LocationIcon.Paragliding // Skydiving Drop Zone

        // Park
        "52e81612bcbc57f1066b7a22" -> LocationIcon.Park // Botanical Garden
        "4bf58dd8d48988d1e4941735" -> LocationIcon.Park // Campground
        "4bf58dd8d48988d1e5941735" -> LocationIcon.Park // Dog Park
        "4bf58dd8d48988d15b941735" -> LocationIcon.Park // Farm
        "4bf58dd8d48988d15f941735" -> LocationIcon.Park // Field
        "4bf58dd8d48988d15a941735" -> LocationIcon.Park // Garden
        "4eb1c0253b7b52c0e1adc2e9" -> LocationIcon.Park // Garden Center
        "4d4b7105d754a06377d81259" -> LocationIcon.Park // Landmarks and Outdoors
        "63be6904847c3692a84b9b5b" -> LocationIcon.Park // Landscaper and Gardener
        "52e81612bcbc57f1066b7a21" -> LocationIcon.Park // National Park
        "63be6904847c3692a84b9be0" -> LocationIcon.Park // Natural Park
        "4bf58dd8d48988d162941735" -> LocationIcon.Park // Other Great Outdoors
        "56aa371be4b08b9a8d57356a" -> LocationIcon.Park // Outdoor Event Space
        "4bf58dd8d48988d163941735" -> LocationIcon.Park // Park
        "5fabfe3599ce226e27fe709a" -> LocationIcon.Park // Picnic Area
        "5fac010d99ce226e27fe7467" -> LocationIcon.Park // Picnic Shelter
        "4bf58dd8d48988d1e7941735" -> LocationIcon.Park // Playground
        "52f2ab2ebcbc57f1066b8b53" -> LocationIcon.Park // RV Park
        "4eb1baf03b7b2c5b1d4306ca" -> LocationIcon.Park // Stable
        "5bae9231bedf3950379f89d0" -> LocationIcon.Park // State or Provincial Park
        "52e81612bcbc57f1066b7a10" -> LocationIcon.Park // Summer Camp
        "63be6904847c3692a84b9be1" -> LocationIcon.Park // Urban Park
        "4bf58dd8d48988d17b941735" -> LocationIcon.Park // Zoo

        // Parking
        "4c38df4de52ce0d596b336e1" -> LocationIcon.Parking // Parking
        "4d954b16a243a5684b65b473" -> LocationIcon.Parking // Rest Area

        // PetStore
        "4e52d2d203646f7c19daa8ae" -> LocationIcon.PetStore // Animal Shelter
        "52f2ab2ebcbc57f1066b8b2a" -> LocationIcon.PetStore // Carpet Store
        "63be6904847c3692a84b9b79" -> LocationIcon.PetStore // Pet Grooming Service
        "5032897c91d4c4b30a586d69" -> LocationIcon.PetStore // Pet Service
        "63be6904847c3692a84b9b7a" -> LocationIcon.PetStore // Pet Sitting and Boarding Service
        "4bf58dd8d48988d100951735" -> LocationIcon.PetStore // Pet Supplies Store

        // Pharmacy
        "63be6904847c3692a84b9be9" -> LocationIcon.Pharmacy // Cannabis Store
        "5745c2e4498e11e7bccabdbd" -> LocationIcon.Pharmacy // Drugstore
        "52c71aaf3cf9994f4e043d17" -> LocationIcon.Pharmacy // Marijuana Dispensary
        "58daa1558bbb0b01f18ec206" -> LocationIcon.Pharmacy // Medical Supply Store
        "4bf58dd8d48988d10f951735" -> LocationIcon.Pharmacy // Pharmacy
        "5744ccdfe4b0c0459246b4cd" -> LocationIcon.Pharmacy // Supplement Store

        // Physician
        "63be6904847c3692a84b9bc7" -> LocationIcon.Physician // Anesthesiologist
        "63be6904847c3692a84b9bc8" -> LocationIcon.Physician // Cardiologist
        "63be6904847c3692a84b9bc9" -> LocationIcon.Physician // Dermatologist
        "4bf58dd8d48988d177941735" -> LocationIcon.Physician // Doctor's Office
        "63be6904847c3692a84b9bca" -> LocationIcon.Physician // Ear, Nose and Throat Doctor
        "63be6904847c3692a84b9bcb" -> LocationIcon.Physician // Family Medicine Doctor
        "63be6904847c3692a84b9bcc" -> LocationIcon.Physician // Gastroenterologist
        "63be6904847c3692a84b9bcd" -> LocationIcon.Physician // General Surgeon
        "63be6904847c3692a84b9bce" -> LocationIcon.Physician // Geriatric Doctor
        "63be6904847c3692a84b9bcf" -> LocationIcon.Physician // Internal Medicine Doctor
        "4bf58dd8d48988d104941735" -> LocationIcon.Physician // Medical Center
        "4f4531b14b9074f6e4fb0103" -> LocationIcon.Physician // Medical Lab
        "63be6904847c3692a84b9bd0" -> LocationIcon.Physician // Neurologist
        "63be6904847c3692a84b9bc3" -> LocationIcon.Physician // Nurse
        "58daa1558bbb0b01f18ec1d0" -> LocationIcon.Physician // Nutritionist
        "63be6904847c3692a84b9bd1" -> LocationIcon.Physician // Obstetrician Gynecologist (Ob-gyn)
        "63be6904847c3692a84b9bd2" -> LocationIcon.Physician // Oncologist
        "63be6904847c3692a84b9bd3" -> LocationIcon.Physician // Ophthalmologist
        "63be6904847c3692a84b9bd5" -> LocationIcon.Physician // Orthopedic Surgeon
        "63be6904847c3692a84b9bc5" -> LocationIcon.Physician // Other Healthcare Professional
        "63be6904847c3692a84b9bd6" -> LocationIcon.Physician // Pathologist
        "63be6904847c3692a84b9bd7" -> LocationIcon.Physician // Pediatrician
        "63be6904847c3692a84b9bc6" -> LocationIcon.Physician // Physician
        "63be6904847c3692a84b9bd8" -> LocationIcon.Physician // Plastic Surgeon
        "63be6904847c3692a84b9bdd" -> LocationIcon.Physician // Podiatrist
        "63be6904847c3692a84b9bd9" -> LocationIcon.Physician // Psychiatrist
        "63be6904847c3692a84b9bc2" -> LocationIcon.Physician // Psychologist
        "63be6904847c3692a84b9bda" -> LocationIcon.Physician // Radiologist
        "63be6904847c3692a84b9bdb" -> LocationIcon.Physician // Respiratory Doctor
        "63be6904847c3692a84b9bdc" -> LocationIcon.Physician // Urologist
        "4d954af4a243a5684765b473" -> LocationIcon.Physician // Veterinarian

        // Pizza
        "4bf58dd8d48988d1ca941735" -> LocationIcon.Pizza // Pizzeria

        // PlaceOfWorship
        "58daa1558bbb0b01f18ec1eb" -> LocationIcon.PlaceOfWorship // Cemevi
        "56aa371be4b08b9a8d5734fc" -> LocationIcon.PlaceOfWorship // Confucian Temple
        "5744ccdfe4b0c0459246b4ac" -> LocationIcon.PlaceOfWorship // Kingdom Hall
        "52e81612bcbc57f1066b7a40" -> LocationIcon.PlaceOfWorship // Monastery
        "52e81612bcbc57f1066b7a41" -> LocationIcon.PlaceOfWorship // Prayer Room
        "4eb1d80a4b900d56c88a45ff" -> LocationIcon.PlaceOfWorship // Shrine
        "5bae9231bedf3950379f89c9" -> LocationIcon.PlaceOfWorship // Sikh Temple
        "4bf58dd8d48988d131941735" -> LocationIcon.PlaceOfWorship // Spiritual Center
        "4bf58dd8d48988d13a941735" -> LocationIcon.PlaceOfWorship // Temple
        "56aa371be4b08b9a8d5734f6" -> LocationIcon.PlaceOfWorship // Terreiro

        // Police
        "4bf58dd8d48988d12e941735" -> LocationIcon.Police // Police Station
        "5310b8e5bcbc57f1066bcbf1" -> LocationIcon.Police // Prison
        "63be6904847c3692a84b9b8f" -> LocationIcon.Police // Security and Safety

        // PostOffice
        "4bf58dd8d48988d172941735" -> LocationIcon.PostOffice // Post Office

        // Pub
        "56aa371ce4b08b9a8d573583" -> LocationIcon.Pub // Apple Wine Pub
        "4bf58dd8d48988d155941735" -> LocationIcon.Pub // Gastropub
        "52e81612bcbc57f1066b7a06" -> LocationIcon.Pub // Irish Pub
        "4bf58dd8d48988d11b941735" -> LocationIcon.Pub // Pub

        // PublicBathroom
        "5744ccdfe4b0c0459246b4c4" -> LocationIcon.PublicBathroom // Public Bathroom

        // Ramen
        "55a59bace4b013909087cb24" -> LocationIcon.Ramen // Ramen Restaurant

        // Restaurant
        "55a5a1ebe4b013909087cbb6" -> LocationIcon.Restaurant // Abruzzo Restaurant
        "503288ae91d4c4b30a586d67" -> LocationIcon.Restaurant // Afghan Restaurant
        "4bf58dd8d48988d1c8941735" -> LocationIcon.Restaurant // African Restaurant
        "57558b36e4b065ecebd306b6" -> LocationIcon.Restaurant // Alsatian Restaurant
        "4bf58dd8d48988d14e941735" -> LocationIcon.Restaurant // American Restaurant
        "55a5a1ebe4b013909087cba7" -> LocationIcon.Restaurant // Aosta Restaurant
        "4bf58dd8d48988d152941735" -> LocationIcon.Restaurant // Arepa Restaurant
        "4bf58dd8d48988d107941735" -> LocationIcon.Restaurant // Argentinian Restaurant
        "5f2c2b7db6d05514c7044837" -> LocationIcon.Restaurant // Armenian Restaurant
        "4bf58dd8d48988d169941735" -> LocationIcon.Restaurant // Australian Restaurant
        "52e81612bcbc57f1066b7a01" -> LocationIcon.Restaurant // Austrian Restaurant
        "57558b36e4b065ecebd306b8" -> LocationIcon.Restaurant // Auvergne Restaurant
        "4bf58dd8d48988d1df931735" -> LocationIcon.Restaurant // BBQ Joint
        "52939ae13cf9994f4e043a3b" -> LocationIcon.Restaurant // Baiano Restaurant
        "5e179ee74ae8e90006e9a746" -> LocationIcon.Restaurant // Bangladeshi Restaurant
        "55a5a1ebe4b013909087cba1" -> LocationIcon.Restaurant // Basilicata Restaurant
        "57558b36e4b065ecebd306bc" -> LocationIcon.Restaurant // Basque Restaurant
        "56aa371ce4b08b9a8d573572" -> LocationIcon.Restaurant // Bavarian Restaurant
        "52e928d0bcbc57f1066b7e97" -> LocationIcon.Restaurant // Belarusian Restaurant
        "52e81612bcbc57f1066b7a02" -> LocationIcon.Restaurant // Belgian Restaurant
        "52e81612bcbc57f1066b79f1" -> LocationIcon.Restaurant // Bistro
        "58daa1558bbb0b01f18ec1ee" -> LocationIcon.Restaurant // Bosnian Restaurant
        "4bf58dd8d48988d16b941735" -> LocationIcon.Restaurant // Brazilian Restaurant
        "57558b36e4b065ecebd306c5" -> LocationIcon.Restaurant // Breton Restaurant
        "52e81612bcbc57f1066b79f4" -> LocationIcon.Restaurant // Buffet
        "56aa371be4b08b9a8d5734f3" -> LocationIcon.Restaurant // Bulgarian Restaurant
        "57558b36e4b065ecebd306c0" -> LocationIcon.Restaurant // Burgundian Restaurant
        "4bf58dd8d48988d153941735" -> LocationIcon.Restaurant // Burrito Restaurant
        "4bf58dd8d48988d17a941735" -> LocationIcon.Restaurant // Cajun and Creole Restaurant
        "55a5a1ebe4b013909087cba4" -> LocationIcon.Restaurant // Calabria Restaurant
        "55a5a1ebe4b013909087cb95" -> LocationIcon.Restaurant // Campanian Restaurant
        "4bf58dd8d48988d144941735" -> LocationIcon.Restaurant // Caribbean Restaurant
        "57558b36e4b065ecebd306cb" -> LocationIcon.Restaurant // Catalan Restaurant
        "63be6904847c3692a84b9b46" -> LocationIcon.Restaurant // Caterer
        "52939a9e3cf9994f4e043a36" -> LocationIcon.Restaurant // Central Brazilian Restaurant
        "57558b36e4b065ecebd306ce" -> LocationIcon.Restaurant // Ch'ti Restaurant
        "58daa1558bbb0b01f18ec1f4" -> LocationIcon.Restaurant // Colombian Restaurant
        "52e81612bcbc57f1066b7a00" -> LocationIcon.Restaurant // Comfort Food Restaurant
        "57558b36e4b065ecebd306d1" -> LocationIcon.Restaurant // Corsican Restaurant
        "52e81612bcbc57f1066b79f2" -> LocationIcon.Restaurant // Creperie
        "53d6c1b0e4b02351e88a83e2" -> LocationIcon.Restaurant // Cretan Restaurant
        "4bf58dd8d48988d154941735" -> LocationIcon.Restaurant // Cuban Restaurant
        "52f2ae52bcbc57f1066b8b81" -> LocationIcon.Restaurant // Czech Restaurant
        "4bf58dd8d48988d146941735" -> LocationIcon.Restaurant // Deli
        "4bf58dd8d48988d147941735" -> LocationIcon.Restaurant // Diner
        "63be6904847c3692a84b9bb5" -> LocationIcon.Restaurant // Dining and Drinking
        "4bf58dd8d48988d108941735" -> LocationIcon.Restaurant // Dumpling Restaurant
        "5744ccdfe4b0c0459246b4d0" -> LocationIcon.Restaurant // Dutch Restaurant
        "4bf58dd8d48988d109941735" -> LocationIcon.Restaurant // Eastern European Restaurant
        "5bae9231bedf3950379f89e1" -> LocationIcon.Restaurant // Egyptian Restaurant
        "55a5a1ebe4b013909087cb89" -> LocationIcon.Restaurant // Emilia Restaurant
        "52939a8c3cf9994f4e043a35" -> LocationIcon.Restaurant // Empanada Restaurant
        "52e81612bcbc57f1066b7a05" -> LocationIcon.Restaurant // English Restaurant
        "4bf58dd8d48988d10a941735" -> LocationIcon.Restaurant // Ethiopian Restaurant
        "4bf58dd8d48988d10b941735" -> LocationIcon.Restaurant // Falafel Restaurant
        "52e81612bcbc57f1066b7a09" -> LocationIcon.Restaurant // Fondue Restaurant
        "4bf58dd8d48988d120951735" -> LocationIcon.Restaurant // Food Court
        "56aa371be4b08b9a8d573550" -> LocationIcon.Restaurant // Food and Beverage Service
        "56aa371ce4b08b9a8d573574" -> LocationIcon.Restaurant // Franconian Restaurant
        "4bf58dd8d48988d10c941735" -> LocationIcon.Restaurant // French Restaurant
        "55a5a1ebe4b013909087cb9b" -> LocationIcon.Restaurant // Friuli Restaurant
        "56aa371ce4b08b9a8d573592" -> LocationIcon.Restaurant // German Pop-Up Restaurant
        "4bf58dd8d48988d10d941735" -> LocationIcon.Restaurant // German Restaurant
        "58daa1558bbb0b01f18ec1c4" -> LocationIcon.Restaurant // Gilaki Restaurant
        "4c2cd86ed066bed06c3c5209" -> LocationIcon.Restaurant // Gluten-Free Restaurant
        "52939af83cf9994f4e043a3d" -> LocationIcon.Restaurant // Goiano Restaurant
        "4bf58dd8d48988d10e941735" -> LocationIcon.Restaurant // Greek Restaurant
        "53d6c1b0e4b02351e88a83d6" -> LocationIcon.Restaurant // Grilled Meat Restaurant
        "52e81612bcbc57f1066b79ff" -> LocationIcon.Restaurant // Halal Restaurant
        "52e81612bcbc57f1066b79fe" -> LocationIcon.Restaurant // Hawaiian Restaurant
        "5f2c32587ff30c0d7ac09638" -> LocationIcon.Restaurant // Honduran Restaurant
        "52e81612bcbc57f1066b79fa" -> LocationIcon.Restaurant // Hungarian Restaurant
        "5bae9231bedf3950379f89e7" -> LocationIcon.Restaurant // Iraqi Restaurant
        "56aa371be4b08b9a8d573529" -> LocationIcon.Restaurant // Israeli Restaurant
        "4bf58dd8d48988d110941735" -> LocationIcon.Restaurant // Italian Restaurant
        "52e81612bcbc57f1066b79fd" -> LocationIcon.Restaurant // Jewish Restaurant
        "5283c7b4e4b094cb91ec88d6" -> LocationIcon.Restaurant // Kokoreç Restaurant
        "52e81612bcbc57f1066b79fc" -> LocationIcon.Restaurant // Kosher Restaurant
        "56aa371be4b08b9a8d573535" -> LocationIcon.Restaurant // Kumpir Restaurant
        "56aa371be4b08b9a8d5734bd" -> LocationIcon.Restaurant // Kumru Restaurant
        "5744ccdfe4b0c0459246b4ca" -> LocationIcon.Restaurant // Kurdish Restaurant
        "4bf58dd8d48988d1be941735" -> LocationIcon.Restaurant // Latin American Restaurant
        "58daa1558bbb0b01f18ec1cd" -> LocationIcon.Restaurant // Lebanese Restaurant
        "55a5a1ebe4b013909087cb98" -> LocationIcon.Restaurant // Ligurian Restaurant
        "4bf58dd8d48988d1bf941735" -> LocationIcon.Restaurant // Mac and Cheese Joint
        "55a5a1ebe4b013909087cbb0" -> LocationIcon.Restaurant // Marche Restaurant
        "5f2c344a5b4c177b9a6dc011" -> LocationIcon.Restaurant // Mauritian Restaurant
        "4bf58dd8d48988d1c0941735" -> LocationIcon.Restaurant // Mediterranean Restaurant
        "4bf58dd8d48988d1c1941735" -> LocationIcon.Restaurant // Mexican Restaurant
        "53d6c1b0e4b02351e88a83da" -> LocationIcon.Restaurant // Meze Restaurant
        "4bf58dd8d48988d115941735" -> LocationIcon.Restaurant // Middle Eastern Restaurant
        "52939aed3cf9994f4e043a3c" -> LocationIcon.Restaurant // Mineiro Restaurant
        "52e81612bcbc57f1066b79f9" -> LocationIcon.Restaurant // Modern European Restaurant
        "53d6c1b0e4b02351e88a83d4" -> LocationIcon.Restaurant // Modern Greek Restaurant
        "4bf58dd8d48988d1c2941735" -> LocationIcon.Restaurant // Molecular Gastronomy Restaurant
        "55a5a1ebe4b013909087cbb3" -> LocationIcon.Restaurant // Molise Restaurant
        "4bf58dd8d48988d1c3941735" -> LocationIcon.Restaurant // Moroccan Restaurant
        "4bf58dd8d48988d157941735" -> LocationIcon.Restaurant // New American Restaurant
        "57558b36e4b065ecebd306d4" -> LocationIcon.Restaurant // Norman Restaurant
        "52939aae3cf9994f4e043a37" -> LocationIcon.Restaurant // Northeastern Brazilian Restaurant
        "52939ab93cf9994f4e043a38" -> LocationIcon.Restaurant // Northern Brazilian Restaurant
        "4bf58dd8d48988d14d941735" -> LocationIcon.Restaurant // Paella Restaurant
        "52e81612bcbc57f1066b79f8" -> LocationIcon.Restaurant // Pakistani Restaurant
        "56aa371ce4b08b9a8d573578" -> LocationIcon.Restaurant // Palatine Restaurant
        "53d6c1b0e4b02351e88a83e0" -> LocationIcon.Restaurant // Patsa Restaurant
        "52e81612bcbc57f1066b79f7" -> LocationIcon.Restaurant // Persian Restaurant
        "4eb1bfa43b7b52c0e1adc2e8" -> LocationIcon.Restaurant // Peruvian Restaurant
        "55a5a1ebe4b013909087cbaa" -> LocationIcon.Restaurant // Piedmontese Restaurant
        "5bae9231bedf3950379f89d4" -> LocationIcon.Restaurant // Poke Restaurant
        "52e81612bcbc57f1066b7a04" -> LocationIcon.Restaurant // Polish Restaurant
        "4def73e84765ae376e57713a" -> LocationIcon.Restaurant // Portuguese Restaurant
        "56aa371be4b08b9a8d5734c7" -> LocationIcon.Restaurant // Poutine Restaurant
        "57558b36e4b065ecebd306d7" -> LocationIcon.Restaurant // Provençal Restaurant
        "5f2c2abab6d05514c70446e4" -> LocationIcon.Restaurant // Puerto Rican Restaurant
        "55a5a1ebe4b013909087cb83" -> LocationIcon.Restaurant // Puglia Restaurant
        "4d4b7105d754a06374d81259" -> LocationIcon.Restaurant // Restaurant
        "4bf58dd8d48988d1c4941735" -> LocationIcon.Restaurant // Restaurant
        "56aa371ce4b08b9a8d57357b" -> LocationIcon.Restaurant // Rhenisch Restaurant
        "55a5a1ebe4b013909087cb8c" -> LocationIcon.Restaurant // Romagna Restaurant
        "55a5a1ebe4b013909087cb92" -> LocationIcon.Restaurant // Roman Restaurant
        "52960bac3cf9994f4e043ac4" -> LocationIcon.Restaurant // Romanian Restaurant
        "5293a7563cf9994f4e043a44" -> LocationIcon.Restaurant // Russian Restaurant
        "4bf58dd8d48988d1bd941735" -> LocationIcon.Restaurant // Salad Restaurant
        "5745c7ac498e5d0483112fdb" -> LocationIcon.Restaurant // Salvadoran Restaurant
        "4bf58dd8d48988d1c5941735" -> LocationIcon.Restaurant // Sandwich Spot
        "55a5a1ebe4b013909087cb8f" -> LocationIcon.Restaurant // Sardinian Restaurant
        "57558b36e4b065ecebd306da" -> LocationIcon.Restaurant // Savoyard Restaurant
        "4bf58dd8d48988d1c6941735" -> LocationIcon.Restaurant // Scandinavian Restaurant
        "56aa371ce4b08b9a8d573587" -> LocationIcon.Restaurant // Schnitzel Restaurant
        "5744ccdde4b0c0459246b4a3" -> LocationIcon.Restaurant // Scottish Restaurant
        "4bf58dd8d48988d1ce941735" -> LocationIcon.Restaurant // Seafood Restaurant
        "55a5a1ebe4b013909087cb86" -> LocationIcon.Restaurant // Sicilian Restaurant
        "56aa371ce4b08b9a8d57357f" -> LocationIcon.Restaurant // Silesian Restaurant
        "56aa371be4b08b9a8d57355a" -> LocationIcon.Restaurant // Slovak Restaurant
        "4bf58dd8d48988d1cd941735" -> LocationIcon.Restaurant // South American Restaurant
        "55a5a1ebe4b013909087cbb9" -> LocationIcon.Restaurant // South Tyrolean Restaurant
        "52939ac53cf9994f4e043a39" -> LocationIcon.Restaurant // Southeastern Brazilian Restaurant
        "52939ad03cf9994f4e043a3a" -> LocationIcon.Restaurant // Southern Brazilian Restaurant
        "4bf58dd8d48988d14f941735" -> LocationIcon.Restaurant // Southern Food Restaurant
        "57558b36e4b065ecebd306ba" -> LocationIcon.Restaurant // Southwestern French Restaurant
        "4bf58dd8d48988d150941735" -> LocationIcon.Restaurant // Spanish Restaurant
        "5413605de4b0ae91d18581a9" -> LocationIcon.Restaurant // Sri Lankan Restaurant
        "4bf58dd8d48988d1cc941735" -> LocationIcon.Restaurant // Steakhouse
        "53e0feef498e5aac066fd8a9" -> LocationIcon.Restaurant // Street Food Gathering
        "56aa371ce4b08b9a8d573576" -> LocationIcon.Restaurant // Swabian Restaurant
        "4bf58dd8d48988d158941735" -> LocationIcon.Restaurant // Swiss Restaurant
        "5bae9231bedf3950379f89da" -> LocationIcon.Restaurant // Syrian Restaurant
        "4bf58dd8d48988d151941735" -> LocationIcon.Restaurant // Taco Restaurant
        "56aa371be4b08b9a8d5734bf" -> LocationIcon.Restaurant // Tantuni Restaurant
        "4bf58dd8d48988d1db931735" -> LocationIcon.Restaurant // Tapas Restaurant
        "52e928d0bcbc57f1066b7e98" -> LocationIcon.Restaurant // Tatar Restaurant
        "56aa371ae4b08b9a8d5734ba" -> LocationIcon.Restaurant // Tex-Mex Restaurant
        "56aa371be4b08b9a8d573538" -> LocationIcon.Restaurant // Theme Restaurant
        "55a5a1ebe4b013909087cbbc" -> LocationIcon.Restaurant // Trentino Restaurant
        "53d6c1b0e4b02351e88a83de" -> LocationIcon.Restaurant // Tsipouro Restaurant
        "5283c7b4e4b094cb91ec88d4" -> LocationIcon.Restaurant // Turkish Home Cooking Restaurant
        "4f04af1f2fb6e1c99f3db0bb" -> LocationIcon.Restaurant // Turkish Restaurant
        "55a5a1ebe4b013909087cb9e" -> LocationIcon.Restaurant // Tuscan Restaurant
        "52e928d0bcbc57f1066b7e96" -> LocationIcon.Restaurant // Ukrainian Restaurant
        "55a5a1ebe4b013909087cbc2" -> LocationIcon.Restaurant // Umbrian Restaurant
        "52e928d0bcbc57f1066b7e9a" -> LocationIcon.Restaurant // Varenyky Restaurant
        "4bf58dd8d48988d1d3941735" -> LocationIcon.Restaurant // Vegan and Vegetarian Restaurant
        "55a5a1ebe4b013909087cbad" -> LocationIcon.Restaurant // Veneto Restaurant
        "56aa371be4b08b9a8d573558" -> LocationIcon.Restaurant // Venezuelan Restaurant
        "52e928d0bcbc57f1066b7e9b" -> LocationIcon.Restaurant // West-Ukrainian Restaurant
        "5bae9231bedf3950379f89ea" -> LocationIcon.Restaurant // Yemeni Restaurant
        "5744ccdfe4b0c0459246b4d3" -> LocationIcon.Restaurant // Yucatecan Restaurant

        // Rugby
        "63be6904847c3692a84b9c14" -> LocationIcon.Rugby // Rugby
        "52e81612bcbc57f1066b7a2c" -> LocationIcon.Rugby // Rugby Pitch

        // School
        "56aa371ce4b08b9a8d573570" -> LocationIcon.School // Adult Education
        "63be6904847c3692a84b9b9f" -> LocationIcon.School // Art School
        "5744ccdfe4b0c0459246b4c7" -> LocationIcon.School // Child Care Service
        "63be6904847c3692a84b9ba0" -> LocationIcon.School // Computer Training School
        "58daa1558bbb0b01f18ec200" -> LocationIcon.School // Culinary School
        "4f4532974b9074f6e4fb0104" -> LocationIcon.School // Daycare
        "52e81612bcbc57f1066b7a42" -> LocationIcon.School // Driving School
        "4bf58dd8d48988d13b941735" -> LocationIcon.School // Education
        "4f4533804b9074f6e4fb0105" -> LocationIcon.School // Elementary School
        "52e81612bcbc57f1066b7a49" -> LocationIcon.School // Flight School
        "4bf58dd8d48988d13d941735" -> LocationIcon.School // High School
        "52e81612bcbc57f1066b7a48" -> LocationIcon.School // Language School
        "4f4533814b9074f6e4fb0106" -> LocationIcon.School // Middle School
        "4f04b10d2fb6e1c99f3db0be" -> LocationIcon.School // Music School
        "4f4533814b9074f6e4fb0107" -> LocationIcon.School // Nursery School
        "52e81612bcbc57f1066b7a45" -> LocationIcon.School // Preschool
        "63be6904847c3692a84b9ba1" -> LocationIcon.School // Primary and Secondary School
        "52e81612bcbc57f1066b7a46" -> LocationIcon.School // Private School
        "52e81612bcbc57f1066b7a47" -> LocationIcon.School // Religious School
        "56aa371be4b08b9a8d5734f9" -> LocationIcon.School // Samba School
        "4bf58dd8d48988d1ad941735" -> LocationIcon.School // Trade School
        "63be6904847c3692a84b9b95" -> LocationIcon.School // Tutoring Service
        "63be6904847c3692a84b9bb0" -> LocationIcon.School // Youth Organization

        // Shopping
        "5267e446e4b0ec79466e48c4" -> LocationIcon.Shopping // Adult Store
        "4bf58dd8d48988d116951735" -> LocationIcon.Shopping // Antique Store
        "4bf58dd8d48988d127951735" -> LocationIcon.Shopping // Arts and Crafts Store
        "63be6904847c3692a84b9be2" -> LocationIcon.Shopping // Auction House
        "52f2ab2ebcbc57f1066b8b32" -> LocationIcon.Shopping // Baby Store
        "4eb1bdf03b7b55596b4a7491" -> LocationIcon.Shopping // Camera Store
        "4bf58dd8d48988d117951735" -> LocationIcon.Shopping // Candy Store
        "52f2ab2ebcbc57f1066b8b31" -> LocationIcon.Shopping // Chocolate Store
        "52f2ab2ebcbc57f1066b8b3b" -> LocationIcon.Shopping // Christmas Market
        "5454144b498ec1f095bff2f2" -> LocationIcon.Shopping // Construction Supplies Store
        "4bf58dd8d48988d10c951735" -> LocationIcon.Shopping // Cosmetics Store
        "589ddde98ae3635c072819ee" -> LocationIcon.Shopping // Duty-free Store
        "52f2ab2ebcbc57f1066b8b3a" -> LocationIcon.Shopping // Fireworks Store
        "4bf58dd8d48988d1f7941735" -> LocationIcon.Shopping // Flea Market
        "56aa371be4b08b9a8d573505" -> LocationIcon.Shopping // Floating Market
        "52f2ab2ebcbc57f1066b8b24" -> LocationIcon.Shopping // Framing Store
        "4bf58dd8d48988d128951735" -> LocationIcon.Shopping // Gift Store
        "4bf58dd8d48988d112951735" -> LocationIcon.Shopping // Hardware Store
        "4bf58dd8d48988d1fb941735" -> LocationIcon.Shopping // Hobby Store
        "52f2ab2ebcbc57f1066b8b25" -> LocationIcon.Shopping // Knitting Store
        "52f2ab2ebcbc57f1066b8b2b" -> LocationIcon.Shopping // Leather Goods Store
        "52f2ab2ebcbc57f1066b8b29" -> LocationIcon.Shopping // Luggage Store
        "50be8ee891d4fa8dcc7199a7" -> LocationIcon.Shopping // Market
        "63be6904847c3692a84b9bb8" -> LocationIcon.Shopping // Marketplace
        "4bf58dd8d48988d1ff941735" -> LocationIcon.Shopping // Miscellaneous Store
        "56aa371be4b08b9a8d57354a" -> LocationIcon.Shopping // Mobility Store
        "4bf58dd8d48988d1fe941735" -> LocationIcon.Shopping // Music Store
        "53e510b7498ebcb1801b55d4" -> LocationIcon.Shopping // Night Market
        "4bf58dd8d48988d121951735" -> LocationIcon.Shopping // Office Supply Store
        "52f2ab2ebcbc57f1066b8b35" -> LocationIcon.Shopping // Outlet Store
        "63be6904847c3692a84b9bf3" -> LocationIcon.Shopping // Packaging Supply Store
        "63be6904847c3692a84b9bf4" -> LocationIcon.Shopping // Party Supply Store
        "52f2ab2ebcbc57f1066b8b34" -> LocationIcon.Shopping // Pawn Shop
        "52f2ab2ebcbc57f1066b8b23" -> LocationIcon.Shopping // Perfume Store
        "52f2ab2ebcbc57f1066b8b3d" -> LocationIcon.Shopping // Pop-Up Store
        "52f2ab2ebcbc57f1066b8b28" -> LocationIcon.Shopping // Print Store
        "4bf58dd8d48988d10d951735" -> LocationIcon.Shopping // Record Store
        "4d4b7105d754a06378d81259" -> LocationIcon.Shopping // Retail
        "4bf58dd8d48988d123951735" -> LocationIcon.Shopping // Smoke Shop
        "52f2ab2ebcbc57f1066b8b1b" -> LocationIcon.Shopping // Souvenir Store
        "52f2ab2ebcbc57f1066b8b21" -> LocationIcon.Shopping // Stationery Store
        "52f2ab2ebcbc57f1066b8b54" -> LocationIcon.Shopping // Stoop Sale
        "5267e4d8e4b0ec79466e48c5" -> LocationIcon.Shopping // Street Fair
        "63be6904847c3692a84b9bfc" -> LocationIcon.Shopping // Tobacco Store
        "4bf58dd8d48988d1f3941735" -> LocationIcon.Shopping // Toy Store
        "5bae9231bedf3950379f89c3" -> LocationIcon.Shopping // Trade Fair
        "56aa371be4b08b9a8d57355c" -> LocationIcon.Shopping // Vape Store
        "4bf58dd8d48988d10b951735" -> LocationIcon.Shopping // Video Games Store
        "4bf58dd8d48988d126951735" -> LocationIcon.Shopping // Video Store
        "52e816a6bcbc57f1066b7a54" -> LocationIcon.Shopping // Warehouse or Wholesale Store

        // ShoppingMall
        "52f2ab2ebcbc57f1066b8b42" -> LocationIcon.ShoppingMall // Big Box Store
        "4bf58dd8d48988d1f6941735" -> LocationIcon.ShoppingMall // Department Store
        "5744ccdfe4b0c0459246b4df" -> LocationIcon.ShoppingMall // Outlet Mall
        "4bf58dd8d48988d1fd941735" -> LocationIcon.ShoppingMall // Shopping Mall
        "5744ccdfe4b0c0459246b4dc" -> LocationIcon.ShoppingMall // Shopping Plaza

        // Skateboarding
        "4bf58dd8d48988d1f1941735" -> LocationIcon.Skateboarding // Board Store
        "4bf58dd8d48988d167941735" -> LocationIcon.Skateboarding // Skate Park
        "5bae9231bedf3950379f89d2" -> LocationIcon.Skateboarding // Skate Store
        "63be6904847c3692a84b9c17" -> LocationIcon.Skateboarding // Skating
        "4bf58dd8d48988d168941735" -> LocationIcon.Skateboarding // Skating Rink

        // Skiing
        "4eb1c0ed3b7b52c0e1adc2ea" -> LocationIcon.Skiing // Ski Chairlift
        "4bf58dd8d48988d1ec941735" -> LocationIcon.Skiing // Ski Chalet
        "4bf58dd8d48988d1eb941735" -> LocationIcon.Skiing // Ski Lodge
        "4bf58dd8d48988d1e9941735" -> LocationIcon.Skiing // Ski Resort and Area
        "56aa371be4b08b9a8d573566" -> LocationIcon.Skiing // Ski Store
        "63be6904847c3692a84b9b4b" -> LocationIcon.Skiing // Skin Care Clinic
        "63be6904847c3692a84b9c19" -> LocationIcon.Skiing // Snow Sports

        // Soccer
        "4bf58dd8d48988d1b7941735" -> LocationIcon.Soccer // College Soccer Field
        "63be6904847c3692a84b9c1a" -> LocationIcon.Soccer // Soccer
        "63be6904847c3692a84b9c1b" -> LocationIcon.Soccer // Soccer Club
        "4cce455aebf7b749d5e191f5" -> LocationIcon.Soccer // Soccer Field
        "63be6904847c3692a84b9bf8" -> LocationIcon.Soccer // Soccer Store

        // Soup
        "4bf58dd8d48988d1dd931735" -> LocationIcon.Soup // Soup Spot

        // Sports
        "63be6904847c3692a84b9bfd" -> LocationIcon.Sports // Athletic Field
        "4bf58dd8d48988d1e4931735" -> LocationIcon.Sports // Bowling Alley
        "52e81612bcbc57f1066b7a2f" -> LocationIcon.Sports // Bowling Green
        "503289d391d4c4b30a586d6a" -> LocationIcon.Sports // Climbing Gym
        "63be6904847c3692a84b9b22" -> LocationIcon.Sports // Country Club
        "63be6904847c3692a84b9c04" -> LocationIcon.Sports // Equestrian Facility
        "52e81612bcbc57f1066b7a0f" -> LocationIcon.Sports // Fishing Area
        "52f2ab2ebcbc57f1066b8b16" -> LocationIcon.Sports // Fishing Store
        "52e81612bcbc57f1066b7a11" -> LocationIcon.Sports // Gun Range
        "52f2ab2ebcbc57f1066b8b19" -> LocationIcon.Sports // Gun Store
        "63be6904847c3692a84b9c0d" -> LocationIcon.Sports // Hunting Area
        "50aaa5234b90af0d42d5de12" -> LocationIcon.Sports // Hunting Supply Store
        "52e81612bcbc57f1066b79e6" -> LocationIcon.Sports // Laser Tag Center
        "52f2ab2ebcbc57f1066b8b22" -> LocationIcon.Sports // Outdoor Supply Store
        "5032829591d4c4b30a586d5e" -> LocationIcon.Sports // Paintball Field
        "52e81612bcbc57f1066b7a26" -> LocationIcon.Sports // Recreation Center
        "52e81612bcbc57f1066b79e9" -> LocationIcon.Sports // Roller Rink
        "63be6904847c3692a84b9c16" -> LocationIcon.Sports // Running Club
        "63be6904847c3692a84b9bf7" -> LocationIcon.Sports // Running Store
        "63be6904847c3692a84b9c15" -> LocationIcon.Sports // Running and Track
        "5bae9231bedf3950379f89c5" -> LocationIcon.Sports // Sporting Event
        "4bf58dd8d48988d1f2941735" -> LocationIcon.Sports // Sporting Goods Retail
        "52e81612bcbc57f1066b7a2e" -> LocationIcon.Sports // Sports Club
        "4f4528bc4b90abdf24c9de85" -> LocationIcon.Sports // Sports and Recreation
        "4bf58dd8d48988d106941735" -> LocationIcon.Sports // Track

        // Stadium
        "4bf58dd8d48988d18c941735" -> LocationIcon.Stadium // Baseball Stadium
        "4bf58dd8d48988d18b941735" -> LocationIcon.Stadium // Basketball Stadium
        "4bf58dd8d48988d1b4941735" -> LocationIcon.Stadium // College Stadium
        "4bf58dd8d48988d189941735" -> LocationIcon.Stadium // Football Stadium
        "4bf58dd8d48988d185941735" -> LocationIcon.Stadium // Hockey Stadium
        "56aa371be4b08b9a8d573556" -> LocationIcon.Stadium // Rugby Stadium
        "4bf58dd8d48988d188941735" -> LocationIcon.Stadium // Soccer Stadium
        "4bf58dd8d48988d184941735" -> LocationIcon.Stadium // Stadium
        "4e39a891bd410d7aed40cbc2" -> LocationIcon.Stadium // Tennis Stadium
        "4bf58dd8d48988d187941735" -> LocationIcon.Stadium // Track Stadium

        // Subway
        "4bf58dd8d48988d1fd931735" -> LocationIcon.Subway // Metro Station

        // Supermarket
        "4bf58dd8d48988d11d951735" -> LocationIcon.Supermarket // Butcher
        "4bf58dd8d48988d11e951735" -> LocationIcon.Supermarket // Cheese Store
        "58daa1558bbb0b01f18ec1ca" -> LocationIcon.Supermarket // Dairy Store
        "4bf58dd8d48988d1fa941735" -> LocationIcon.Supermarket // Farmers Market
        "4bf58dd8d48988d10e951735" -> LocationIcon.Supermarket // Fish Market
        "63be6904847c3692a84b9b47" -> LocationIcon.Supermarket // Food Distribution Center
        "4bf58dd8d48988d1f9941735" -> LocationIcon.Supermarket // Food and Beverage Retail
        "52f2ab2ebcbc57f1066b8b1c" -> LocationIcon.Supermarket // Fruit and Vegetable Store
        "4bf58dd8d48988d1f5941735" -> LocationIcon.Supermarket // Gourmet Store
        "4bf58dd8d48988d118951735" -> LocationIcon.Supermarket // Grocery Store
        "50aa9e744b90af0d42d5de0e" -> LocationIcon.Supermarket // Health Food Store
        "52f2ab2ebcbc57f1066b8b2c" -> LocationIcon.Supermarket // Herbs and Spices Store
        "5f2c41945b4c177b9a6dc7d6" -> LocationIcon.Supermarket // Imported Food Store
        "63be6904847c3692a84b9bef" -> LocationIcon.Supermarket // Kosher Store
        "58daa1558bbb0b01f18ec1e8" -> LocationIcon.Supermarket // Kuruyemişçi Shop
        "63be6904847c3692a84b9bf0" -> LocationIcon.Supermarket // Meat and Seafood Store
        "52f2ab2ebcbc57f1066b8b45" -> LocationIcon.Supermarket // Organic Grocery
        "56aa371be4b08b9a8d573564" -> LocationIcon.Supermarket // Sausage Store
        "52f2ab2ebcbc57f1066b8b46" -> LocationIcon.Supermarket // Supermarket
        "58daa1558bbb0b01f18ec1e5" -> LocationIcon.Supermarket // Turşucu Shop

        // Surfing
        "4bf58dd8d48988d1e2941735" -> LocationIcon.Surfing // Beach
        "52e81612bcbc57f1066b7a12" -> LocationIcon.Surfing // Dive Spot
        "52f2ab2ebcbc57f1066b8b1a" -> LocationIcon.Surfing // Dive Store
        "52e81612bcbc57f1066b7a30" -> LocationIcon.Surfing // Nudist Beach
        "63be6904847c3692a84b9c20" -> LocationIcon.Surfing // Scuba Diving Instructor
        "4bf58dd8d48988d1e3941735" -> LocationIcon.Surfing // Surf Spot
        "63be6904847c3692a84b9bf9" -> LocationIcon.Surfing // Surf Store
        "63be6904847c3692a84b9c21" -> LocationIcon.Surfing // Surfing
        "63be6904847c3692a84b9c1c" -> LocationIcon.Surfing // Water Sports

        // Swimming
        "52e81612bcbc57f1066b7a28" -> LocationIcon.Swimming // Bathing Area
        "4bf58dd8d48988d105941735" -> LocationIcon.Swimming // Gym Pool
        "4bf58dd8d48988d160941735" -> LocationIcon.Swimming // Hot Spring
        "4bf58dd8d48988d132951735" -> LocationIcon.Swimming // Hotel Pool
        "4bf58dd8d48988d1e3931735" -> LocationIcon.Swimming // Pool Hall
        "52e81612bcbc57f1066b7a44" -> LocationIcon.Swimming // Swim School
        "63be6904847c3692a84b9c22" -> LocationIcon.Swimming // Swimming
        "63be6904847c3692a84b9c23" -> LocationIcon.Swimming // Swimming Club
        "4bf58dd8d48988d15e941735" -> LocationIcon.Swimming // Swimming Pool
        "63be6904847c3692a84b9b63" -> LocationIcon.Swimming // Swimming Pool Maintenance and Service
        "63be6904847c3692a84b9bfb" -> LocationIcon.Swimming // Swimming Pool Supply Store

        // Synagogue
        "4bf58dd8d48988d139941735" -> LocationIcon.Synagogue // Synagogue

        // Taxi
        "4bf58dd8d48988d130951735" -> LocationIcon.Taxi // Taxi
        "53fca564498e1a175f32528b" -> LocationIcon.Taxi // Taxi Stand

        // Tennis
        "52e81612bcbc57f1066b7a2b" -> LocationIcon.Tennis // Badminton Court
        "4e39a9cebd410d7aed40cbc4" -> LocationIcon.Tennis // College Tennis Court
        "63be6904847c3692a84b9c10" -> LocationIcon.Tennis // Racquet Sport Club
        "63be6904847c3692a84b9c0f" -> LocationIcon.Tennis // Racquet Sports
        "63be6904847c3692a84b9c11" -> LocationIcon.Tennis // Racquetball Club
        "52e81612bcbc57f1066b7a2d" -> LocationIcon.Tennis // Squash Court
        "63be6904847c3692a84b9c12" -> LocationIcon.Tennis // Tennis
        "63be6904847c3692a84b9c13" -> LocationIcon.Tennis // Tennis Club
        "4e39a956bd410d7aed40cbc3" -> LocationIcon.Tennis // Tennis Court
        "63be6904847c3692a84b9bfa" -> LocationIcon.Tennis // Tennis Store

        // Theater
        "56aa371be4b08b9a8d5734db" -> LocationIcon.Theater // Amphitheater
        "4bf58dd8d48988d173941735" -> LocationIcon.Theater // Auditorium
        "56aa371be4b08b9a8d5734cf" -> LocationIcon.Theater // Ballroom
        "4bf58dd8d48988d1ac941735" -> LocationIcon.Theater // College Theater
        "4bf58dd8d48988d134941735" -> LocationIcon.Theater // Dance Studio
        "4bf58dd8d48988d135941735" -> LocationIcon.Theater // Indie Theater
        "4bf58dd8d48988d136941735" -> LocationIcon.Theater // Opera House
        "4bf58dd8d48988d1f2931735" -> LocationIcon.Theater // Performing Arts Venue
        "4bf58dd8d48988d137941735" -> LocationIcon.Theater // Theater

        // Train
        "4f4531504b9074f6e4fb0102" -> LocationIcon.Train // Platform
        "4bf58dd8d48988d129951735" -> LocationIcon.Train // Rail Station
        "4bf58dd8d48988d12a951735" -> LocationIcon.Train // Train

        // Tram
        "4bf58dd8d48988d1ec931735" -> LocationIcon.Tram // Airport Tram Station
        "4bf58dd8d48988d1fc931735" -> LocationIcon.Tram // Light Rail Station
        "52f2ab2ebcbc57f1066b8b51" -> LocationIcon.Tram // Tram Station

        // University
        "4bf58dd8d48988d198941735" -> LocationIcon.University // College Academic Building
        "4bf58dd8d48988d197941735" -> LocationIcon.University // College Administrative Building
        "4bf58dd8d48988d199941735" -> LocationIcon.University // College Arts Building
        "4bf58dd8d48988d1af941735" -> LocationIcon.University // College Auditorium
        "4bf58dd8d48988d1a1941735" -> LocationIcon.University // College Cafeteria
        "4bf58dd8d48988d1a0941735" -> LocationIcon.University // College Classroom
        "4bf58dd8d48988d19a941735" -> LocationIcon.University // College Communications Building
        "4bf58dd8d48988d19e941735" -> LocationIcon.University // College Engineering Building
        "4bf58dd8d48988d1b2941735" -> LocationIcon.University // College Gym
        "4bf58dd8d48988d19d941735" -> LocationIcon.University // College History Building
        "4bf58dd8d48988d1a5941735" -> LocationIcon.University // College Lab
        "4bf58dd8d48988d1a7941735" -> LocationIcon.University // College Library
        "4bf58dd8d48988d19c941735" -> LocationIcon.University // College Math Building
        "4bf58dd8d48988d1aa941735" -> LocationIcon.University // College Quad
        "4bf58dd8d48988d1a9941735" -> LocationIcon.University // College Rec Center
        "4bf58dd8d48988d1a3941735" -> LocationIcon.University // College Residence Hall
        "4bf58dd8d48988d19b941735" -> LocationIcon.University // College Science Building
        "4bf58dd8d48988d19f941735" -> LocationIcon.University // College Technology Building
        "4bf58dd8d48988d1b6941735" -> LocationIcon.University // College Track
        "4d4b7105d754a06372d81259" -> LocationIcon.University // College and University
        "4bf58dd8d48988d1a2941735" -> LocationIcon.University // Community College
        "4bf58dd8d48988d1a8941735" -> LocationIcon.University // General College & University
        "4bf58dd8d48988d1ae941735" -> LocationIcon.University // University

        // Volleyball
        "4eb1bf013b7b6f98df247e07" -> LocationIcon.Volleyball // Volleyball Court
        else -> null
    }
}


