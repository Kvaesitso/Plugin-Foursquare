package de.mm20.launcher2.plugin.foursquare

import android.util.Log
import de.mm20.launcher2.plugin.foursquare.api.FsqPlace
import de.mm20.launcher2.plugin.foursquare.api.FsqPlaceSearch
import de.mm20.launcher2.plugin.foursquare.api.FsqLatLon
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.request
import io.ktor.http.URLProtocol
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class FoursquareApiClient(
    private val apiKey: String,
) {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            })
        }
        defaultRequest {
            url {
                protocol = URLProtocol.HTTPS
                host = "api.foursquare.com"
            }
            header("Authorization", apiKey)
        }
    }

    suspend fun placesSearch(
        query: String,
        ll: FsqLatLon,
        radius: Int,
        fields: Set<String>? = null,
    ): FsqPlaceSearch {
        Log.d("MM20", "placesSearch: $query, $ll, $radius, $fields")
        return client.get {
            url {
                path("v3", "places", "search")
                parameter("query", query)
                parameter("ll", "${ll.latitude},${ll.longitude}")
                parameter("radius", radius.toString())
                if (fields != null) {
                    parameter("fields", fields.joinToString(","))
                }
            }
        }.also {
            Log.d("MM20", it.request.url.toString())
        }.body<FsqPlaceSearch>().also {
            Log.d("MM20", it.toString())
        }
    }

    suspend fun placeById(
        fsqId: String,
        fields: Set<String>? = null,
    ): FsqPlace? {
        return client.get {
            url {
                path("v3", "places", fsqId)
                if (fields != null) {
                    parameters {
                        append("fields", fields.joinToString(","))
                    }
                }
            }
        }.body()
    }
}