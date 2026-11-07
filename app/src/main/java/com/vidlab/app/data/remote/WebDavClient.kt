package com.vidlab.app.data.remote

import com.vidlab.app.data.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavClient(
    private val baseUrl: String = "http://127.0.0.1:8080"
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun listDirectory(path: String = "/"): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanPath = if (path.startsWith("/")) path else "/$path"
            val targetUrl = "$baseUrl$cleanPath"

            val propfindXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                    <D:prop>
                        <D:resourcetype/>
                        <D:getcontentlength/>
                    </D:prop>
                </D:propfind>
            """.trimIndent()

            val request = Request.Builder()
                .url(targetUrl)
                .method("PROPFIND", propfindXml.toRequestBody())
                .header("Depth", "1")
                .build()

            val response = client.newCall(request).execute()
            val xml = response.body?.string() ?: ""

            parsePropfindXml(xml, cleanPath)
        }
    }

    private fun parsePropfindXml(xml: String, requestedPath: String): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        if (xml.isBlank()) return files

        try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(xml.byteInputStream())

            val responses = doc.getElementsByTagNameNS("*", "response")
            for (i in 0 until responses.length) {
                val respElement = responses.item(i) as org.w3c.dom.Element
                val href = respElement.getElementsByTagNameNS("*", "href").item(0)?.textContent ?: continue
                val decodedHref = java.net.URLDecoder.decode(href, "UTF-8")

                val resType = respElement.getElementsByTagNameNS("*", "resourcetype").item(0) as? org.w3c.dom.Element
                val isDir = resType?.getElementsByTagNameNS("*", "collection")?.length ?: 0 > 0

                val cleanHref = decodedHref.trimEnd('/')
                val cleanReq = requestedPath.trimEnd('/')
                if (cleanHref.equals(cleanReq, ignoreCase = true) || cleanHref.isEmpty()) continue

                val name = cleanHref.substringAfterLast('/')
                val sizeStr = respElement.getElementsByTagNameNS("*", "getcontentlength").item(0)?.textContent
                val size = sizeStr?.toLongOrNull() ?: 0L

                files.add(
                    MediaFile(
                        name = name,
                        path = cleanHref,
                        isDirectory = isDir,
                        size = size
                    )
                )
            }
        } catch (_: Exception) {}

        return files.sortedWith(compareByDescending<MediaFile> { it.isDirectory }.thenBy { it.name })
    }
}
