package ru.embroidery.shop.sketch

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import ru.embroidery.shop.files.ImageStorage
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.util.UUID

private const val MAX_REFERENCES = 4
private const val MAX_REFERENCE_BYTES = 2 * 1024 * 1024
private const val MAX_PROMPT_LENGTH = 12_000

internal data class SketchModels(
    @field:Positive val version: Int,
    val responseModel: String,
    val imageModel: String,
    @field:NotBlank @field:Size(max = MAX_PROMPT_LENGTH) val systemPrompt: String,
)

internal data class SaveSketchSettings(
    @field:Valid val selection: SketchModels,
    @field:Size(max = MAX_REFERENCES) val retainedReferenceIds: List<UUID>,
)

internal data class SketchModelOption(val id: String, val label: String)
internal data class SketchReferenceInfo(val id: UUID, val name: String, val mediaType: String)
internal data class SketchPromptReference(val id: UUID, val name: String, val mediaType: String, val content: ByteArray)
internal data class SketchConfiguration(val selection: SketchModels, val references: List<SketchPromptReference>)

internal data class SketchModelOptions(
    val selection: SketchModels,
    val responseModels: List<SketchModelOption>,
    val imageModels: List<SketchModelOption>,
    val references: List<SketchReferenceInfo>,
)

@Repository
internal class SketchModelSettings(private val db: JdbcClient) {
    private fun current(): SketchModels = db.sql("""
        SELECT version, response_model, image_model, system_prompt FROM sketch_model_settings WHERE singleton
    """).query { row, _ ->
        SketchModels(row.getInt("version"), row.getString("response_model"), row.getString("image_model"),
            row.getString("system_prompt"))
    }.single()

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun configuration() = SketchConfiguration(current(), db.sql("""
        SELECT id, name, media_type, content FROM sketch_prompt_references ORDER BY position
    """).query { row, _ ->
        SketchPromptReference(row.getObject("id", UUID::class.java), row.getString("name"),
            row.getString("media_type"), row.getBytes("content"))
    }.list())

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options() = options(current())

    private fun options(selection: SketchModels) = SketchModelOptions(selection, responseModels, imageModels, db.sql("""
        SELECT id, name, media_type FROM sketch_prompt_references ORDER BY position
    """).query { row, _ ->
        SketchReferenceInfo(row.getObject("id", UUID::class.java), row.getString("name"), row.getString("media_type"))
    }.list())

    fun reference(id: UUID): SketchPromptReference? = db.sql("""
        SELECT name, media_type, content FROM sketch_prompt_references WHERE id = :id
    """).param("id", id).query { row, _ ->
        SketchPromptReference(id, row.getString("name"), row.getString("media_type"), row.getBytes("content"))
    }.optional().orElse(null)

    @Transactional
    fun save(input: SaveSketchSettings, additions: List<SketchPromptReference>): SketchModelOptions {
        val selection = input.selection
        val retained = input.retainedReferenceIds.toSet()
        if (retained.size != input.retainedReferenceIds.size || retained.size + additions.size > MAX_REFERENCES) {
            badRequest("Choose up to four distinct sketch references")
        }
        if (responseModels.none { it.id == selection.responseModel } || imageModels.none { it.id == selection.imageModel }) {
            badRequest("Choose a supported sketch model")
        }
        val prompt = selection.systemPrompt.trim()
        if (prompt.isEmpty() || '\u0000' in prompt) badRequest("Enter a valid system prompt")
        val saved = db.sql("""
            UPDATE sketch_model_settings SET version = version + 1,
                response_model = :responseModel, image_model = :imageModel, system_prompt = :prompt
            WHERE singleton AND version = :version
            RETURNING version
        """).param("version", selection.version).param("responseModel", selection.responseModel)
            .param("imageModel", selection.imageModel).param("prompt", prompt).query(Int::class.java).optional()
            .orElseThrow { conflict("The sketch model settings have changed") }

        val existing = db.sql("SELECT id, position FROM sketch_prompt_references")
            .query { row, _ -> row.getObject("id", UUID::class.java) to row.getInt("position") }.list().toMap()
        if (!existing.keys.containsAll(retained)) badRequest("Sketch reference not found")
        for (id in existing.keys - retained) {
            db.sql("DELETE FROM sketch_prompt_references WHERE id = :id").param("id", id).update()
        }
        val usedPositions = retained.map(existing::getValue).toSet()
        val freePositions = (1..MAX_REFERENCES).filterNot { it in usedPositions }
        additions.zip(freePositions).forEach { (reference, position) ->
            db.sql("""
                INSERT INTO sketch_prompt_references (id, position, name, media_type, content)
                VALUES (:id, :position, :name, :mediaType, :content)
            """).param("id", reference.id).param("position", position).param("name", reference.name)
                .param("mediaType", reference.mediaType).param("content", reference.content).update()
        }
        return options(selection.copy(version = saved, systemPrompt = prompt))
    }

    private companion object {
        val responseModels = listOf(
            SketchModelOption("gemini-3-1-flash-lite", "Gemini 3.1 Flash-Lite"),
            SketchModelOption("gemini-2-5-flash-lite", "Gemini 2.5 Flash-Lite"),
        )
        val imageModels = listOf(
            SketchModelOption("flash", "FLUX.2 Flash — быстрые эскизы"),
            SketchModelOption("standard", "FLUX.2 Standard"),
        )
    }
}

@RestController
@RequestMapping("/api/staff/sketch-settings")
internal class SketchModelSettingsController(private val settings: SketchModelSettings, private val images: ImageStorage) {
    @GetMapping
    fun get(): ResponseEntity<SketchModelOptions> = response(settings.options())

    @PutMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun update(
        @Valid @RequestPart("settings") input: SaveSketchSettings,
        @RequestPart("files", required = false) files: List<MultipartFile>?,
    ): ResponseEntity<SketchModelOptions> {
        val uploads = files.orEmpty()
        if (input.retainedReferenceIds.size + uploads.size > MAX_REFERENCES) {
            badRequest("Choose up to four distinct sketch references")
        }
        return response(settings.save(input, uploads.map(::readReference)))
    }

    @GetMapping("/references/{id}")
    fun download(@PathVariable id: UUID): ResponseEntity<ByteArray> {
        val reference = settings.reference(id) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .contentType(MediaType.parseMediaType(reference.mediaType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(reference.name, Charsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(reference.content)
    }

    private fun readReference(file: MultipartFile): SketchPromptReference {
        if (file.size !in 1..MAX_REFERENCE_BYTES.toLong()) badRequest("Sketch references must be between 1 byte and 2 MiB")
        val name = file.originalFilename.orEmpty().substringAfterLast('/').substringAfterLast('\\').trim()
        if (name.isEmpty() || name.length > 160 || name.any(Char::isISOControl)) badRequest("Invalid sketch reference filename")
        val id = UUID.randomUUID()
        return when (name.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg", "png" -> images.withValidatedImage(file) { image ->
                SketchPromptReference(id, name, image.mediaType, Files.readAllBytes(image.path))
            }
            "txt" -> {
                val text = try {
                    Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(file.bytes)).toString().removePrefix("\uFEFF").trim()
                } catch (_: CharacterCodingException) {
                    badRequest("Choose a UTF-8 text reference")
                }
                if (text.isBlank() || text.length > MAX_PROMPT_LENGTH || text.any { it.isISOControl() && it !in "\t\r\n" }) {
                    badRequest("Choose a UTF-8 text reference")
                }
                SketchPromptReference(id, name, "text/plain", text.toByteArray())
            }
            else -> badRequest("Choose JPEG, PNG or TXT for sketch references")
        }
    }

    private fun response(options: SketchModelOptions) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(options)
}
