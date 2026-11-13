package ru.embroidery.shop.catalog

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import ru.embroidery.shop.Pagination
import ru.embroidery.shop.badRequest
import ru.embroidery.shop.conflict
import java.util.UUID

@Service
class CatalogService(
    private val catalogs: CatalogRepository,
    private val products: ProductRepository,
    private val images: CatalogImageRepository
) {
    fun listCatalogs(page: Pagination, publicView: Boolean) = catalogs.list(page, publicView)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun publishedCatalog(slug: String, page: Pagination): CatalogContents {
        val catalog = catalogs.published(slug)
        return CatalogContents(catalog, products.inCatalog(catalog.id, page, publicView = true))
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun catalogForStaff(id: UUID, page: Pagination): CatalogContents =
        CatalogContents(catalogs.get(id), products.inCatalog(id, page, publicView = false))

    @Transactional
    fun createCatalog(input: CatalogForm): Catalog {
        images.requireReady(listOfNotNull(input.coverImageId))
        return catalogs.get(catalogs.create(input))
    }

    @Transactional
    fun updateCatalog(id: UUID, input: CatalogForm): Catalog {
        images.requireReady(listOfNotNull(input.coverImageId))
        catalogs.update(id, input)
        return catalogs.get(id)
    }

    @Transactional
    fun placeProduct(catalogId: UUID, productId: UUID, displayOrder: Int) {
        catalogs.get(catalogId)
        products.lock(productId)
        catalogs.placeProduct(catalogId, productId, displayOrder)
    }

    @Transactional
    fun removeProductFromCatalog(catalogId: UUID, productId: UUID) {
        catalogs.get(catalogId)
        products.lock(productId)
        catalogs.removeProduct(catalogId, productId)
    }

    fun listProducts(page: Pagination) = products.list(page)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun publishedProduct(catalogSlug: String, productSlug: String): Product =
        details(products.published(catalogSlug, productSlug), publicView = true)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun productForStaff(id: UUID): Product = details(products.get(id), publicView = false)

    @Transactional
    fun createProduct(input: ProductForm): Product {
        if (input.published) conflict("Add variants to a draft product before publishing it")
        checkProductImages(input.imageIds)
        val id = products.create(input)
        products.replaceImages(id, input.imageIds)
        return details(products.get(id), publicView = false)
    }

    @Transactional
    fun updateProduct(id: UUID, input: ProductForm): Product {
        products.lock(id)
        checkProductImages(input.imageIds)
        if (input.published && (input.imageIds.isEmpty() || !products.hasVariant(id))) {
            conflict("Add an image and a variant before publishing the product")
        }
        products.update(id, input)
        products.replaceImages(id, input.imageIds)
        return details(products.get(id), publicView = false)
    }

    @Transactional
    fun createVariant(productId: UUID, input: VariantForm): ProductVariant {
        checkAvailability(input)
        products.lock(productId)
        return products.createVariant(productId, input)
    }

    @Transactional
    fun updateVariant(productId: UUID, variantId: UUID, input: VariantForm): ProductVariant {
        checkAvailability(input)
        products.lock(productId)
        return products.updateVariant(productId, variantId, input)
    }

    @Transactional
    fun removeVariant(productId: UUID, variantId: UUID) {
        val published = products.lock(productId)
        products.removeVariant(productId, variantId)
        if (published && !products.hasVariant(productId)) conflict("Unpublish the product before removing its last variant")
    }

    private fun details(product: Product, publicView: Boolean) =
        product.copy(images = products.images(product.id, publicView), variants = products.variants(product.id))

    private fun checkProductImages(ids: List<UUID>) {
        if (ids.toSet().size != ids.size) badRequest("Product images must be distinct")
        images.requireReady(ids)
    }

    private fun checkAvailability(input: VariantForm) {
        if (input.availability == Availability.MADE_TO_ORDER) {
            if (input.productionDays == null || input.productionDays <= 0) {
                badRequest("Made-to-order variants require a positive production time")
            }
        } else if (input.productionDays != null) {
            badRequest("Production time is only available for made-to-order variants")
        }
    }
}
