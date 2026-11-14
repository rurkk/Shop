# Фотографии для локальной проверки

Изображения сгенерированы встроенным ImageGen. Это иллюстрации для тестового стенда, а не фотографии товаров Shop или заказов покупателей. Фронтенд получает их только после загрузки через API с помощью `seed_storefront.py`.

Скрипт также добавляет демонстрационные замеры для худи «Воля» и ориентиры по вышивке и доставке. Для публикации рабочей витрины нужны фактические замеры и данные магазина.

- `hero.jpg`, `coupe-detail.jpg`: редакционный макроснимок чёрного худи в боковом свете, серебристая вышивка ретро-купе справа, свободное место под текст слева, без логотипов.
- `coupe-source.jpg`: серебристое ретро-купе конца 1980-х в тёмной студии, ракурс в три четверти, без читаемых номеров и брендов.
- `horse*.jpg`, `rosette*.jpg`: студийные фото сложенных чёрного худи с вышивкой коня и молочной футболки с розеткой; отдельные крупные планы.
- `forest*.jpg`, `bird*.jpg`: студийные фото серого свитшота с вышивкой елей и бордового худи с вышивкой птицы; отдельные крупные планы.

<details>
<summary>Промпты ImageGen</summary>

Баннер:

> Use case: ads-marketing / product-mockup. Generate ONE landscape 3:2 editorial photograph for a minimal independent embroidery workshop's website hero. Real premium washed-black charcoal heavyweight cotton hoodie, dramatic editorial macro view of its chest laid flat in low raking light. Visible seams and large folds. A real small detailed silver-white thread embroidery of a vintage late-1980s boxy sports coupe in side three-quarter view, no readable branding, at x=69%, y=51%. Clear raised tactile stitches, subtle grey thread, no ink or printed graphics. The left 45% is pure near-black cotton with soft shadow and very quiet texture, reserved for website type, but render absolutely NO lettering, graphic overlays, or watermark. Keep the composition legible when cropped narrow around x=68% for mobile. Deep graphite and cool monochrome tones, high contrast, luxury fashion e-commerce, analog grain, realistic textiles. No people, no background props, no UI. Wide landscape.

Исходное фото:

> Use case: photorealistic-natural. A single landscape photograph that will serve as the illustrative source photo paired with a sample embroidery of a late 1980s silver boxy sports coupe, front three-quarter view from the driver side, facing to the left. Classic wedge front, discreet low rear spoiler, no legible logo, plate or people. Parked in an empty quiet dark gray industrial photography studio with overcast diffuse light. Analog muted graphite color and subtle grain, natural realistic automotive photo, close enough that the car dominates most of the frame while staying completely visible. Absolutely no lettering, no UI, no watermarks.

Худи и футболка:

> Use case: product-mockup. One square 2x2 fashion product photography contact sheet, four equal squares abutting exactly at center with no gutters. Top left: whole folded black hoodie embroidered with a small cream galloping horse, on warm gray. Top right: whole folded bone white T-shirt embroidered with a small espresso folk rosette, on pale greige. Bottom left: detailed macro photo of the same galloping horse thread embroidery on black cotton. Bottom right: detailed macro photo of the same rosette on white cotton. Overhead window light, realistic textile textures, minimal editorial composition, no people, no lettering, no watermark. Keep each garment and motif within its quadrant.

Свитшот и бордовое худи:

> Use case: product-mockup. Square high-end fashion photography contact sheet for a test collection inspired by northern folk motifs. EXACTLY FOUR perfectly square photographic tiles in a 2x2 grid, hard straight cuts at the exact center, NO separators or gutters and absolutely no typography or logos. Upper left: whole folded stone-gray heavy crewneck sweatshirt with small realistic dark thread embroidery of two northern pine trees on the chest, pale grey studio backdrop. Upper right: whole folded dark oxblood heavyweight hoodie with small muted cream thread embroidery of a folk bird with spread wings, on warm greige studio backdrop. Lower left: close-up macro of the stone grey cotton with the same real dark thread pine tree motif on the chest and subtle folds. Lower right: macro of the same dark oxblood cotton with the same cream thread folk bird. Soft window light from left, minimal modern editorial art direction, real premium soft cotton, understated restrained palette. No people, no hangers, no type, no watermarks. The quadrants will be cut into individual website photos. Keep all garments and motifs comfortably within each quadrant.

</details>
