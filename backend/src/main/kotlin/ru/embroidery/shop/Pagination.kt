package ru.embroidery.shop

data class Page<T>(val items: List<T>, val page: Int, val size: Int, val hasMore: Boolean)

class Pagination(private val page: Int, private val size: Int) {
    init {
        if (page < 0 || size !in 1..100) badRequest("Page must be non-negative and size must be between 1 and 100")
    }

    val limit = size + 1
    val offset = page.toLong() * size

    fun <T> result(rows: List<T>) = Page(rows.take(size), page, size, rows.size > size)
}
