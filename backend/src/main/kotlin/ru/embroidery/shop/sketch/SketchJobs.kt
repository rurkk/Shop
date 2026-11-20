package ru.embroidery.shop.sketch

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

internal enum class SketchJobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }

internal data class StartSketchJob(
    @field:Valid val placement: SketchPlacement,
    @field:Min(1) @field:Max(3) val maxVariants: Int = 1,
    val previousFileId: UUID? = null,
    @field:Size(max = 2000) val feedback: String? = null,
)

internal data class SketchJobSummary(
    val id: UUID,
    val status: SketchJobStatus,
    val createdAt: OffsetDateTime,
    val failure: String?,
    val placement: SketchPlacement?,
)

internal data class SketchDraft(
    val fileId: UUID,
    val jobId: UUID,
    val previousFileId: UUID?,
    val interpretation: String?,
    val published: Boolean,
)

internal data class SketchWorkspace(
    val available: Boolean,
    val latestJob: SketchJobSummary?,
    val drafts: List<SketchDraft>,
    val panelEstimates: Map<GarmentSide, SketchPanelEstimate>,
    val panelPreviews: Map<GarmentSide, SketchPanelPreview>,
)

internal data class ClaimedSketchJob(
    val id: UUID,
    val orderId: UUID,
    val requestedBy: String,
    val throughSequence: Long,
    val maxVariants: Int,
    val previousFileId: UUID?,
    val feedback: String?,
    val placement: SketchPlacement,
)

@Repository
internal class SketchJobs(private val db: JdbcClient) {
    private val summaryMapper = RowMapper { rs, _ ->
        SketchJobSummary(
            rs.getObject("id", UUID::class.java), SketchJobStatus.valueOf(rs.getString("status")),
            rs.getObject("created_at", OffsetDateTime::class.java), rs.getString("failure"), rs.placement(),
        )
    }

    fun latest(orderId: UUID): SketchJobSummary? = db.sql("""
        SELECT id, status, created_at, failure, garment_side, panel_width_mm, panel_height_mm,
               embroidery_width_mm, embroidery_height_mm, frame_x_percent, frame_y_percent
        FROM sketch_generation_jobs
        WHERE order_id = :order ORDER BY created_at DESC, id DESC LIMIT 1
    """).param("order", orderId).query(summaryMapper).optional().orElse(null)

    fun hasVariant(orderId: UUID, fileId: UUID): Boolean = db.sql("""
        SELECT EXISTS (SELECT 1 FROM sketch_generation_variants WHERE order_id = :order AND file_id = :file)
    """).param("order", orderId).param("file", fileId).query(Boolean::class.java).single()

    fun drafts(orderId: UUID): List<SketchDraft> = db.sql("""
        SELECT v.file_id, v.job_id, v.interpretation, j.previous_file_id, s.file_id IS NOT NULL AS published
        FROM sketch_generation_variants v
        JOIN sketch_generation_jobs j ON j.id = v.job_id
        LEFT JOIN sketches s ON s.file_id = v.file_id
        WHERE v.order_id = :order ORDER BY j.created_at DESC, j.id DESC, v.position
    """).param("order", orderId).query { rs, _ ->
        SketchDraft(
            rs.getObject("file_id", UUID::class.java), rs.getObject("job_id", UUID::class.java),
            rs.getObject("previous_file_id", UUID::class.java), rs.getString("interpretation"),
            rs.getBoolean("published"),
        )
    }.list()

    fun enqueue(orderId: UUID, staff: String, throughSequence: Long, input: StartSketchJob): SketchJobSummary? =
        db.sql("""
            INSERT INTO sketch_generation_jobs
                (id, order_id, requested_by, through_sequence, max_variants, previous_file_id, feedback, status,
                 garment_side, panel_width_mm, panel_height_mm, embroidery_width_mm, embroidery_height_mm,
                 frame_x_percent, frame_y_percent)
            VALUES (:id, :order, :staff, :sequence, :variants, :previous, :feedback, 'QUEUED',
                    :side, :panelWidth, :panelHeight, :embroideryWidth, :embroideryHeight, :frameX, :frameY)
            ON CONFLICT (order_id) WHERE status IN ('QUEUED', 'RUNNING') DO NOTHING
            RETURNING id, status, created_at, failure, garment_side, panel_width_mm, panel_height_mm,
                      embroidery_width_mm, embroidery_height_mm, frame_x_percent, frame_y_percent
        """).param("id", UUID.randomUUID()).param("order", orderId).param("staff", staff)
            .param("sequence", throughSequence).param("variants", input.maxVariants)
            .param("previous", input.previousFileId, Types.OTHER).param("feedback", input.feedback, Types.VARCHAR)
            .param("side", input.placement.side.name)
            .param("panelWidth", input.placement.panelWidthMm).param("panelHeight", input.placement.panelHeightMm)
            .param("embroideryWidth", input.placement.embroideryWidthMm)
            .param("embroideryHeight", input.placement.embroideryHeightMm)
            .param("frameX", input.placement.position.xPercent).param("frameY", input.placement.position.yPercent)
            .query(summaryMapper).optional().orElse(null)

    fun claim(): ClaimedSketchJob? = db.sql("""
        WITH next AS (
            SELECT id FROM sketch_generation_jobs WHERE status = 'QUEUED'
            ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
        )
        UPDATE sketch_generation_jobs j SET status = 'RUNNING', started_at = now()
        FROM next WHERE j.id = next.id
        RETURNING j.id, j.order_id, j.requested_by, j.through_sequence, j.max_variants, j.previous_file_id, j.feedback,
                  j.garment_side, j.panel_width_mm, j.panel_height_mm, j.embroidery_width_mm, j.embroidery_height_mm,
                  j.frame_x_percent, j.frame_y_percent
    """).query { rs, _ ->
        ClaimedSketchJob(
            rs.getObject("id", UUID::class.java), rs.getObject("order_id", UUID::class.java),
            rs.getString("requested_by"), rs.getLong("through_sequence"), rs.getInt("max_variants"),
            rs.getObject("previous_file_id", UUID::class.java), rs.getString("feedback"),
            requireNotNull(rs.placement()),
        )
    }.optional().orElse(null)

    private fun ResultSet.placement(): SketchPlacement? = getString("garment_side")?.let { side ->
        SketchPlacement(
            GarmentSide.valueOf(side), getInt("panel_width_mm"), getInt("panel_height_mm"),
            getInt("embroidery_width_mm"), getInt("embroidery_height_mm"),
            SketchPosition(getInt("frame_x_percent"), getInt("frame_y_percent")),
        )
    }

    fun expireInterrupted() {
        db.sql("""
            UPDATE sketch_generation_jobs SET status = 'FAILED', failure = 'INTERRUPTED'
            WHERE status = 'RUNNING' AND started_at < now() - interval '15 minutes'
        """).update()
    }

    fun addVariant(job: ClaimedSketchJob, fileId: UUID, position: Int, interpretation: String?) {
        db.sql("""
            INSERT INTO sketch_generation_variants (order_id, job_id, file_id, position, interpretation)
            VALUES (:order, :job, :file, :position, :interpretation)
        """).param("order", job.orderId).param("job", job.id).param("file", fileId).param("position", position)
            .param("interpretation", interpretation, Types.VARCHAR).update()
    }

    fun succeed(id: UUID): Boolean = db.sql("""
        UPDATE sketch_generation_jobs SET status = 'SUCCEEDED' WHERE id = :id AND status = 'RUNNING'
    """).param("id", id).update() == 1

    fun fail(id: UUID, reason: String) {
        db.sql("""
            UPDATE sketch_generation_jobs SET status = 'FAILED', failure = :reason
            WHERE id = :id AND status = 'RUNNING'
        """).param("id", id).param("reason", reason).update()
    }
}
