package de.mm20.launcher2.plugin.foursquare

import android.content.Context
import android.util.Log
import de.mm20.launcher2.plugin.foursquare.api.FsqLatLon
import de.mm20.launcher2.plugin.foursquare.api.FsqPlace
import de.mm20.launcher2.plugin.foursquare.api.FsqPlaceSearch
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.parameters
import io.ktor.http.path
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.io.IOException

class FoursquareApiClient(
    private val context: Context,
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
        }
    }

    suspend fun placesSearch(
        query: String,
        ll: FsqLatLon,
        radius: Int,
        fields: Set<String>? = null,
        language: String? = null,
        apiKey: String? = null,
    ): FsqPlaceSearch {
        val response = client.get {
            url {
                path("v3", "places", "search")
                parameter("query", query)
                parameter("ll", "${ll.latitude},${ll.longitude}")
                parameter("radius", radius.toString())
                if (fields != null) {
                    parameter("fields", fields.joinToString(","))
                }
            }
            if (language != null) {
                header("Accept-Language", language)
            }
            header("Authorization", apiKey ?: this@FoursquareApiClient.apiKey.first())
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalArgumentException("Unauthorized. Invalid API key?; body ${response.bodyAsText()}")
        } else if (response.status != HttpStatusCode.OK) {
            throw IOException("API error: status ${response.status.value}; body ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun placeById(
        fsqId: String,
        fields: Set<String>? = null,
        language: String? = null,
        apiKey: String? = null,
    ): FsqPlace? {
        val response = client.get {
            url {
                path("v3", "places", fsqId)
                if (fields != null) {
                    parameters {
                        append("fields", fields.joinToString(","))
                    }
                }
            }
            if (language != null) {
                header("Accept-Language", language)
            }
            header("Authorization", apiKey ?: this@FoursquareApiClient.apiKey.first())
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            throw IllegalArgumentException("Unauthorized. Invalid API key?; body ${response.bodyAsText()}")
        } else if (response.status != HttpStatusCode.OK) {
            throw IOException("API error: status ${response.status.value}; body ${response.bodyAsText()}")
        }
        return response.body()
    }



    suspend fun setApiKey(apiKey: String) {
        context.dataStore.updateData {
            it.copy(apiKey = apiKey)
        }
    }

    suspend fun testApiKey(apiKey: String): Boolean {
        return try {
            placeById(
                fsqId = "51a2445e5019c80b56934c75",
                apiKey = apiKey
            )
            return true
        } catch (e: IllegalArgumentException) {
            Log.e("OwmApiClient", "Invalid API key", e)
            return false
        }
    }

    val apiKey: Flow<String?> = context.dataStore.data.map { it.apiKey }
}