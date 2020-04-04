package run.qontract.test

import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.ClientRequestException
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.request
import io.ktor.client.statement.readText
import io.ktor.client.statement.request
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.toMap
import kotlinx.coroutines.runBlocking
import run.qontract.core.HttpRequest
import run.qontract.core.HttpResponse
import run.qontract.core.ServerSetupStateException
import run.qontract.core.startLinesWith
import run.qontract.core.utilities.valueMapToPlainJsonString
import run.qontract.core.value.EmptyString
import run.qontract.core.value.Value
import run.qontract.fake.toParams
import java.io.IOException
import java.net.MalformedURLException
import java.net.URISyntaxException
import java.net.URL
import java.util.*

class HttpClient(private val baseURL: String) : TestExecutor {
    private val serverStateURL = "/_server_state"
    @KtorExperimentalAPI
    @Throws(IOException::class, URISyntaxException::class)
    override fun execute(request: HttpRequest): HttpResponse {
        val ktorClient = io.ktor.client.HttpClient(CIO)
        val url = URL(request.getURL(baseURL))

        val startTime = Date()

        return runBlocking {
            val ktorResponse: io.ktor.client.statement.HttpResponse = ktorClient.request(url) {
                this.method = io.ktor.http.HttpMethod.parse(request.method as String)

                val listOfExcludedHeaders = listOf("content-type", "content-length")
                request.headers
                        .map {Triple(it.key?.trim() ?: "", it.key?.trim()?.toLowerCase() ?: "", it.value?.trim() ?: "")}
                        .forEach { (key, loweredKey, value) ->
                            if(loweredKey !in listOfExcludedHeaders) {
                                this.headers[key] = value
                            }
                }

                if(request.formFields.isNotEmpty()) {
                    val parameters = request.formFields.mapValues { listOf(it.value) }.toList()
                    this.body = FormDataContent(parametersOf(*parameters.toTypedArray()))
                }
                else if (request.body != null) {
                    this.body = when {
                        request.headers.containsKey("Content-Type") -> TextContent(request.bodyString, ContentType.parse(request.headers["Content-Type"] as String))
                        else -> request.bodyString
                    }
                }
            }

            val endTime = Date()

            val outboundRequest: HttpRequest = ktorHttpRequestToHttpRequest(ktorResponse.request, request)
            println(">> Request Start At $startTime")
            println(outboundRequest.toLogString("-> "))

            ktorResponseToHttpResponse(ktorResponse).also {
                println(it.toLogString("<- "))
                println("<< Response At $endTime == ")
                println()
            }
        }
    }

    @KtorExperimentalAPI
    @Throws(MalformedURLException::class, URISyntaxException::class, ServerSetupStateException::class)
    override fun setServerState(serverState: Map<String, Value>) {
        if (serverState.isEmpty()) return

        val ktorClient = io.ktor.client.HttpClient(CIO)
        val url = URL(baseURL + serverStateURL)

        val startTime = Date()

        runBlocking {
            println("# >> Request Sent At $startTime")
            println(startLinesWith(valueMapToPlainJsonString(serverState), "# "))

            val ktorResponse: io.ktor.client.statement.HttpResponse = ktorClient.request(url) {
                this.method = HttpMethod.Post
                this.contentType(ContentType.Application.Json)
                this.body = valueMapToPlainJsonString(serverState)
            }

            val endTime = Date()

            println("# << Complete At $endTime")

            ktorResponseToHttpResponse(ktorResponse)

            if(ktorResponse.status != HttpStatusCode.OK)
                throw ServerSetupStateException("Server setup API responded with ${ktorResponse.status}")
        }
    }

}

private fun ktorHttpRequestToHttpRequest(request: io.ktor.client.request.HttpRequest, qontractRequest: HttpRequest): HttpRequest {
    val(body, formFields) =
        when(request.content) {
            is FormDataContent -> Pair(EmptyString, qontractRequest.formFields)
            is TextContent -> Pair(qontractRequest.body ?: EmptyString, emptyMap<String, String>())
            else -> throw ContractHTTPException("Unknown type of body content sent in the request")
        }

    val requestHeaders = HashMap(request.headers.toMap().mapValues { it.value[0] })

    return HttpRequest(method = request.method.value,
            path = request.url.fullPath,
            headers = requestHeaders,
            body = body,
            queryParams = toParams(request.url.parameters),
            formFields = formFields)
}

private suspend fun ktorResponseToHttpResponse(ktorResponse: io.ktor.client.statement.HttpResponse): HttpResponse =
        HttpResponse(ktorResponse.status.value,
                try {
                    ktorResponse.readText()
                } catch (e: ClientRequestException) {
                    ""
                },
                ktorResponse.headers.toMap().mapValues { it.value.first() }.toMutableMap())

