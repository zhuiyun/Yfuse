package com.yfuse.watch

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
internal data class PaddleOcrSubmitRequest(
    val fileUrl: String,
    val model: String,
    val optionalPayload: JsonObject,
)

internal data class PaddleOcrJobSnapshot(
    val state: String,
    val resultUrl: String? = null,
    val errorMessage: String? = null,
)

internal object PaddleOcrResponseParser {
    fun submittedJobId(body: String): String? =
        runCatching {
            ingestionJson
                .parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonObject
                ?.get("jobId")
                ?.jsonPrimitive
                ?.content
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    fun jobSnapshot(body: String): PaddleOcrJobSnapshot? =
        runCatching {
            val data = ingestionJson.parseToJsonElement(body).jsonObject["data"]?.jsonObject ?: return null
            val state =
                data["state"]
                    ?.jsonPrimitive
                    ?.content
                    ?.lowercase()
                    ?.takeIf(String::isNotBlank) ?: return null
            PaddleOcrJobSnapshot(
                state = state,
                resultUrl =
                    data["resultUrl"]
                        ?.jsonObject
                        ?.get("jsonUrl")
                        ?.jsonPrimitive
                        ?.content
                        ?.takeIf(String::isNotBlank),
                errorMessage = data["errorMsg"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
            )
        }.getOrNull()

    fun isSubmitQueueFull(body: String): Boolean =
        runCatching {
            ingestionJson
                .parseToJsonElement(body)
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull() == PADDLE_QUEUE_FULL_CODE
        }.getOrDefault(false)

    fun extractMarkdownText(jsonLines: String): String? =
        runCatching {
            val results =
                jsonLines
                    .lineSequence()
                    .filter(String::isNotBlank)
                    .mapNotNull { line ->
                        ingestionJson.parseToJsonElement(line).jsonObject["result"]?.jsonObject
                    }.toList()
            val markdown =
                results
                    .asSequence()
                    .flatMap { result ->
                        result["layoutParsingResults"]?.jsonArray.orEmpty().asSequence()
                    }.mapNotNull { layout ->
                        layout.jsonObject["markdown"]
                            ?.jsonObject
                            ?.get("text")
                            ?.jsonPrimitive
                            ?.content
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    }.joinToString("\n")
                    .takeIf(String::isNotBlank)
            markdown ?: results
                .asSequence()
                .flatMap { result -> result["ocrResults"]?.jsonArray.orEmpty().asSequence() }
                .flatMap { ocrResult ->
                    ocrResult.jsonObject["prunedResult"]
                        ?.jsonObject
                        ?.get("rec_texts")
                        ?.jsonArray
                        .orEmpty()
                        .asSequence()
                }.mapNotNull { text ->
                    text.jsonPrimitive.content
                        .trim()
                        .takeIf(String::isNotBlank)
                }.joinToString("\n")
                .takeIf(String::isNotBlank)
        }.getOrNull()
}

internal object OcrSpaceResponseParser {
    fun extractText(body: String): String? =
        runCatching {
            val root = ingestionJson.parseToJsonElement(body).jsonObject
            val errored = root["IsErroredOnProcessing"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (errored) return null
            root["ParsedResults"]
                ?.jsonArray
                .orEmpty()
                .asSequence()
                .mapNotNull { result ->
                    result.jsonObject["ParsedText"]
                        ?.jsonPrimitive
                        ?.content
                        ?.trim()
                        ?.takeIf(String::isNotBlank)
                }.joinToString("\n")
                .takeIf(String::isNotBlank)
        }.getOrNull()

    fun failureSummary(body: String): String =
        runCatching {
            val root = ingestionJson.parseToJsonElement(body).jsonObject
            val exitCode = root["OCRExitCode"]?.jsonPrimitive?.content ?: "unknown"
            val error =
                listOf("ErrorMessage", "ErrorDetails")
                    .mapNotNull { key -> root[key]?.toString()?.takeIf(String::isNotBlank) }
                    .joinToString(";")
                    .replace(Regex("[\\r\\n\\t]"), " ")
                    .take(300)
                    .ifBlank { "none" }
            "exit=$exitCode error=$error"
        }.getOrDefault("unparseable-response")
}
