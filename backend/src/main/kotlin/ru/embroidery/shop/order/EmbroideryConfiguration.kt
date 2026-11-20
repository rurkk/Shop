package ru.embroidery.shop.order

enum class EmbroideryFrame(val sizeMm: Int) { MINI(80), SMALL(120), STANDARD(160), LARGE(220) }

data class EmbroideryConfiguration(
    val frame: EmbroideryFrame,
    val threePhotoBackground: Boolean,
    val licensePlate: Boolean,
)

data class PhotoOrderOption(
    val garment: Garment,
    val frame: EmbroideryFrame,
    val sizeMm: Int,
    val threePhotoBackground: Boolean,
    val priceKopecks: Long?,
)

internal const val BACKGROUND_PHOTO_COUNT = 3

internal val photoOrderOptions = options(Garment.HOODIE, 600_000, 650_000, 740_000, 870_000, 990_000) +
    options(Garment.T_SHIRT, 500_000, 550_000, 640_000, 770_000, 890_000) +
    options(Garment.SWEATSHIRT, null, null, null, null, null)

private fun options(garment: Garment, mini: Long?, small: Long?, standard: Long?, large: Long?, threePhotos: Long?) = listOf(
    PhotoOrderOption(garment, EmbroideryFrame.MINI, EmbroideryFrame.MINI.sizeMm, false, mini),
    PhotoOrderOption(garment, EmbroideryFrame.SMALL, EmbroideryFrame.SMALL.sizeMm, false, small),
    PhotoOrderOption(garment, EmbroideryFrame.STANDARD, EmbroideryFrame.STANDARD.sizeMm, false, standard),
    PhotoOrderOption(garment, EmbroideryFrame.LARGE, EmbroideryFrame.LARGE.sizeMm, false, large),
    PhotoOrderOption(garment, EmbroideryFrame.LARGE, EmbroideryFrame.LARGE.sizeMm, true, threePhotos),
)
