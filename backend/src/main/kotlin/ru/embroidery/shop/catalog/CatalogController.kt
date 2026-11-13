package ru.embroidery.shop.catalog

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/catalogs")
class CatalogController(private val catalog: CatalogService) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") size: Int
    ) = catalog.listCatalogs(CatalogPagination(page, size), publicView = true)

    @GetMapping("/{catalogSlug}")
    fun detail(
        @PathVariable catalogSlug: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") size: Int
    ) = catalog.publishedCatalog(catalogSlug, CatalogPagination(page, size))

    @GetMapping("/{catalogSlug}/products/{productSlug}")
    fun product(@PathVariable catalogSlug: String, @PathVariable productSlug: String) =
        catalog.publishedProduct(catalogSlug, productSlug)
}

@RestController
@RequestMapping("/api/staff/catalogs")
class StaffCatalogController(private val catalog: CatalogService) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") size: Int
    ) = catalog.listCatalogs(CatalogPagination(page, size), publicView = false)

    @GetMapping("/{id}")
    fun detail(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") size: Int
    ) = catalog.catalogForStaff(id, CatalogPagination(page, size))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody input: CatalogForm) = catalog.createCatalog(input)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody input: CatalogForm) = catalog.updateCatalog(id, input)

    @PutMapping("/{catalogId}/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun place(
        @PathVariable catalogId: UUID,
        @PathVariable productId: UUID,
        @Valid @RequestBody input: ProductPlacement
    ) = catalog.placeProduct(catalogId, productId, input.displayOrder)

    @DeleteMapping("/{catalogId}/products/{productId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable catalogId: UUID, @PathVariable productId: UUID) =
        catalog.removeProductFromCatalog(catalogId, productId)
}

@RestController
@RequestMapping("/api/staff/products")
class StaffProductController(private val catalog: CatalogService) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") size: Int
    ) = catalog.listProducts(CatalogPagination(page, size))

    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID) = catalog.productForStaff(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody input: ProductForm) = catalog.createProduct(input)

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody input: ProductForm) = catalog.updateProduct(id, input)

    @PostMapping("/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    fun createVariant(@PathVariable id: UUID, @Valid @RequestBody input: VariantForm) =
        catalog.createVariant(id, input)

    @PutMapping("/{id}/variants/{variantId}")
    fun updateVariant(@PathVariable id: UUID, @PathVariable variantId: UUID, @Valid @RequestBody input: VariantForm) =
        catalog.updateVariant(id, variantId, input)

    @DeleteMapping("/{id}/variants/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeVariant(@PathVariable id: UUID, @PathVariable variantId: UUID) = catalog.removeVariant(id, variantId)
}
