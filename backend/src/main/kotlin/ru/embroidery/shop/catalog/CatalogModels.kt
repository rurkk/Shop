package ru.embroidery.shop.catalog

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import ru.embroidery.shop.ApiFailure
import ru.embroidery.shop.Page
import java.math.BigDecimal
import java.util.UUID

enum class CatalogKind { COLLECTION, SELECTION }
enum class Availability { IN_STOCK, MADE_TO_ORDER, OUT_OF_STOCK }

data class CatalogForm(
    @field:NotBlank @field:Size(max = 80) @field:Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*")
    val slug: String,
    @field:NotBlank @field:Size(max = 160) val name: String,
    val kind: CatalogKind,
    @field:Size(max = 4000) val description: String = "",
    val coverImageId: UUID? = null,
    @field:PositiveOrZero @param:JsonSetter(nulls = Nulls.FAIL) val displayOrder: Int = 0,
    @param:JsonSetter(nulls = Nulls.FAIL) val published: Boolean = false
)

data class ProductForm(
    @field:NotBlank @field:Size(max = 80) @field:Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*")
    val slug: String,
    @field:NotBlank @field:Size(max = 160) val name: String,
    @field:NotBlank @field:Size(max = 160) val design: String,
    @field:NotBlank @field:Size(max = 80) val garmentType: String,
    @field:Size(max = 4000) val description: String = "",
    @field:Valid @field:Size(max = 12) @param:JsonSetter(contentNulls = Nulls.FAIL) val images: List<ProductImageForm>,
    @field:Valid @field:Size(max = 32) @param:JsonSetter(contentNulls = Nulls.FAIL)
    val measurements: List<ProductMeasurements>,
    @param:JsonSetter(nulls = Nulls.FAIL) val published: Boolean = false
)

data class ProductImageForm(val id: UUID, @field:Size(max = 200) val caption: String? = null)

data class ProductMeasurements(
    @field:NotBlank @field:Size(max = 32) val size: String,
    @field:DecimalMin("0.1") @field:DecimalMax("999.9") @field:Digits(integer = 3, fraction = 1)
    val widthCm: BigDecimal,
    @field:DecimalMin("0.1") @field:DecimalMax("999.9") @field:Digits(integer = 3, fraction = 1)
    val lengthCm: BigDecimal,
    @field:DecimalMin("0.1") @field:DecimalMax("999.9") @field:Digits(integer = 3, fraction = 1)
    val sleeveLengthCm: BigDecimal? = null
)

data class VariantForm(
    @field:NotBlank @field:Size(max = 64) @field:Pattern(regexp = "[A-Z0-9][A-Z0-9_-]*")
    val sku: String,
    @field:NotBlank @field:Size(max = 32) val size: String,
    @field:NotBlank @field:Size(max = 80) val color: String,
    @field:Min(1) @field:Max(9_007_199_254_740_991) @param:JsonSetter(nulls = Nulls.FAIL) val priceKopecks: Long,
    val availability: Availability,
    val productionDays: Int? = null
)

data class ProductPlacement(@field:PositiveOrZero @param:JsonSetter(nulls = Nulls.FAIL) val displayOrder: Int)

data class ImageReference(val id: UUID, val url: String)

data class ProductImage(val id: UUID, val url: String, val caption: String?)

internal fun imageReference(id: UUID, public: Boolean) =
    ImageReference(id, if (public) "/api/catalog-images/$id" else "/api/staff/catalog-images/$id")

data class Catalog(
    val id: UUID,
    val slug: String,
    val name: String,
    val description: String,
    val kind: CatalogKind,
    val cover: ImageReference?,
    val displayOrder: Int,
    val published: Boolean
)

data class ProductSummary(
    val id: UUID,
    val slug: String,
    val name: String,
    val garmentType: String,
    val published: Boolean,
    val cover: ImageReference?,
    val fromPriceKopecks: Long?,
    val displayOrder: Int?
)

data class Product(
    val id: UUID,
    val slug: String,
    val name: String,
    val design: String,
    val garmentType: String,
    val description: String,
    val published: Boolean,
    val images: List<ProductImage>,
    val variants: List<ProductVariant>,
    val measurements: List<ProductMeasurements>
)

data class ProductVariant(
    val id: UUID,
    val sku: String,
    val size: String,
    val color: String,
    val priceKopecks: Long,
    val availability: Availability,
    val productionDays: Int?
)

data class CatalogContents(val catalog: Catalog, val products: Page<ProductSummary>)

data class CatalogVariant(
    val productSlug: String,
    val productName: String,
    val garmentType: String,
    val variant: ProductVariant
)

internal fun catalogNotFound(): Nothing = throw ApiFailure(HttpStatus.NOT_FOUND, "Catalog not found")
internal fun productNotFound(): Nothing = throw ApiFailure(HttpStatus.NOT_FOUND, "Product not found")
internal fun variantNotFound(): Nothing = throw ApiFailure(HttpStatus.NOT_FOUND, "Product variant not found")
