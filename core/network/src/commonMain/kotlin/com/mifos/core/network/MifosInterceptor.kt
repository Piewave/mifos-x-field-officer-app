/*
 * Copyright 2024 Mifos Initiative
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * See https://github.com/openMF/mifos-x-field-officer-app/blob/master/LICENSE.md
 */
package com.mifos.core.network

import co.touchlab.kermit.Logger
import com.mifos.core.datastore.UserPreferencesRepository
import com.mifos.core.network.model.PostAuthenticationRequest
import com.mifos.core.network.model.PostAuthenticationResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpClientPlugin
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestPipeline
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MifosInterceptor(
    private val repository: UserPreferencesRepository,
) {
    private val refreshMutex = Mutex()

    companion object Plugin : HttpClientPlugin<ConfigMifos, MifosInterceptor> {

        const val HEADER_TENANT = "Fineract-Platform-TenantId"
        const val HEADER_AUTH = "Authorization"
        private const val AUTHENTICATION_PATH = "authentication"
        private const val CONTENT_TYPE = "Content-Type"
        private val RETRY_ATTEMPTED = AttributeKey<Boolean>("MifosInterceptorRetryAttempted")

        override val key: AttributeKey<MifosInterceptor> = AttributeKey("MifosInterceptor")

        override fun install(plugin: MifosInterceptor, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.State) {
                val tenant = plugin.repository.getServerConfig.value.tenant
                context.header(CONTENT_TYPE, "application/json")
                context.header("Accept", "application/json")
                context.header(HEADER_TENANT, tenant)

                if (!context.isAuthenticationRequest()) {
                    plugin.repository.token?.let { token ->
                        if (token.isNotEmpty()) {
                            context.headers.remove(HEADER_AUTH)
                            context.headers.append(HEADER_AUTH, token)
                        }
                    }
                }
            }

            scope.plugin(HttpSend).intercept { request ->
                if (request.isLoanDisbursementRequest()) {
                    Logger.e("LoanDisbursementHttp") {
                        "Dispatching ${request.method.value} ${request.url}"
                    }
                    println("LoanDisbursementHttp: dispatch ${request.method.value} ${request.url}")
                }

                val initialCall = execute(request)
                if (request.isLoanDisbursementRequest()) {
                    Logger.e("LoanDisbursementHttp") {
                        "Response ${initialCall.response.status.value} for ${request.method.value} ${request.url}"
                    }
                    println("LoanDisbursementHttp: response ${initialCall.response.status.value} ${request.method.value} ${request.url}")
                }

                if (
                    initialCall.response.status != HttpStatusCode.Unauthorized ||
                    request.isAuthenticationRequest() ||
                    request.attributes.getOrNull(RETRY_ATTEMPTED) == true
                ) {
                    return@intercept initialCall
                }

                val refreshed = plugin.refreshJwtSession(scope)
                if (!refreshed) return@intercept initialCall

                request.attributes.put(RETRY_ATTEMPTED, true)
                plugin.repository.token?.takeIf { it.isNotBlank() }?.let { token ->
                    request.headers.remove(HEADER_AUTH)
                    request.headers.append(HEADER_AUTH, token)
                }

                val retryCall = execute(request)
                if (request.isLoanDisbursementRequest()) {
                    Logger.e("LoanDisbursementHttp") {
                        "Retry response ${retryCall.response.status.value} for ${request.method.value} ${request.url}"
                    }
                    println("LoanDisbursementHttp: retryResponse ${retryCall.response.status.value} ${request.method.value} ${request.url}")
                }

                retryCall
            }
        }

        override fun prepare(block: ConfigMifos.() -> Unit): MifosInterceptor {
            val config = ConfigMifos().apply(block)
            return MifosInterceptor(config.repository)
        }
    }

    private suspend fun refreshJwtSession(client: HttpClient): Boolean {
        return refreshMutex.withLock {
            val currentUser = repository.userData.first()
            val username = currentUser.username?.takeIf { it.isNotBlank() } ?: return@withLock forceLogout()
            val password = currentUser.password?.takeIf { it.isNotBlank() } ?: return@withLock forceLogout()

            val refreshedUserResponse = runCatching {
                client.post("${repository.instanceUrl}$AUTHENTICATION_PATH") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        PostAuthenticationRequest(
                            username = username,
                            password = password,
                        ),
                    )
                }.body<PostAuthenticationResponse>()
            }.getOrNull() ?: return@withLock forceLogout()

            val refreshedToken =
                refreshedUserResponse.base64EncodedAuthenticationKey?.takeIf { it.isNotBlank() }
                    ?: return@withLock forceLogout()

            if (refreshedUserResponse.authenticated != true) {
                return@withLock forceLogout()
            }

            repository.updateUser(
                currentUser.copy(
                    userId = refreshedUserResponse.userId ?: currentUser.userId,
                    base64EncodedAuthenticationKey = refreshedToken,
                    isAuthenticated = true,
                    officeId = refreshedUserResponse.officeId ?: currentUser.officeId,
                    officeName = refreshedUserResponse.officeName ?: currentUser.officeName,
                    permissions = refreshedUserResponse.permissions ?: currentUser.permissions,
                ),
            )

            true
        }
    }

    private suspend fun forceLogout(): Boolean {
        repository.logOut()
        return false
    }
}

private fun HttpRequestBuilder.isAuthenticationRequest(): Boolean {
    val normalizedUrl = url.toString().substringBefore('?')
    return normalizedUrl.endsWith("/authentication") || normalizedUrl.endsWith("authentication")
}

private fun HttpRequestBuilder.isLoanDisbursementRequest(): Boolean {
    val requestUrl = url.toString()
    return method == HttpMethod.Post &&
        requestUrl.contains("/loans/") &&
        requestUrl.contains("command=disburse")
}

class ConfigMifos {
    lateinit var repository: UserPreferencesRepository
}
