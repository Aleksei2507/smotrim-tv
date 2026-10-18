# Компоненты и материалы

Смотрим TV 0.5.0 использует следующие компоненты:

| Компонент | Лицензия / источник |
| --- | --- |
| AndroidX Media3, WebKit и остальные AndroidX-компоненты | Apache License 2.0, `licenses/Apache-2.0.txt` |
| TorrServer MatriX.147 | GPL-3.0, `licenses/TorrServer-GPL-3.0.txt`; отдельный процесс с HTTP API на loopback |
| libtorrent4j и libtorrent (только тестовый раздающий, не входят в пользовательский APK) | MIT и BSD, `licenses/libtorrent4j-MIT.txt`, `licenses/libtorrent-BSD.txt` |
| jsoup | MIT, `licenses/jsoup-MIT.txt` |
| Список и адреса потоков iptv-org | Unlicense, `licenses/iptv-org-UNLICENSE.txt`; это лицензия списка, не самих телетрансляций |
| Данные Wikidata | CC0; использованы названия по идентификаторам IMDb |

Полные тексты перечисленных лицензий также включены в APK в `assets/licenses/`. Лицензии транзитивных компонентов поставляются в метаданных их дистрибутивов; сведения о версиях находятся в Gradle-файлах проекта.

Метаданные и изображения каталога получены через Cinemeta / images.metahub.space. Права на названия, описания, постеры, кадры, логотипы и транслируемое содержание принадлежат соответствующим правообладателям. Приложение обращается к публичным API и страницам JacRed, Rutor и APIBay; исходный код этих серверов в APK не включён.

Для Android-теста используется трейлер **Sintel**, © Blender Foundation, [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Файл получен с [официального сервера Blender](https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4). Он лежит только в `app/src/androidTest/assets/` и не включается в пользовательский APK. Тестовый торрент генерируется локально и обслуживается локальным BitTorrent-раздающим и HTTP-трекером.

## Исходники TorrServer

Используются немодифицированные Android arm64 / arm7 сборки из [официального релиза MatriX.147](https://github.com/YouROK/TorrServer/releases/tag/MatriX.147). Соответствующий полный архив исходников и сборочных файлов приложен отдельно как `TorrServer-MatriX.147-source.tar.gz`. Точная копия доступна [у автора](https://github.com/YouROK/TorrServer/archive/refs/tags/MatriX.147.tar.gz). Происхождение и SHA-256 бинарников записаны в `third-party/TorrServer.json`. Сервер запускается только на время просмотра, слушает loopback и использует случайный пароль для каждого запуска.

## Programme guide

The optional default XMLTV programme guide is provided by https://epg.pw/xmltv.html. Programme availability and accuracy depend on the provider. Users may supply another XMLTV URL. Only a bounded local cache of near-term schedule data is retained.

Сезоны и серии также получаются из Cinemeta: `/catalog/series/top/skip=0.json`, `/catalog/series/top/search=…json` и `/meta/series/{IMDb ID}.json`. Формат описан в [Stremio Metadata response](https://stremio.github.io/stremio-addon-sdk/api/responses/meta.html). Эти данные не подтверждают наличие раздач.


## Дополнительные каталоги в 0.5.0

TMDB: https://www.themoviedb.org. This product uses the TMDB API but is not endorsed or certified by TMDB. Официальный логотип из https://www.themoviedb.org/about/logos-attribution включён в assets/ui/tmdb.svg без изменения цвета и пропорций. Источник SVG: /assets/v4/logos/v2/blue_short-8e7b30f73a4020692ccca9c88bafe5dcb6f8a62a4c6bc55cd9ba82bb2cd95f6c.svg.

Kinopoisk API Unofficial: https://kinopoiskapiunofficial.tech/documentation/api/. Независимый сервис, не официальный API Яндекса. Используется личный ключ пользователя. Каталоги предоставляют метаданные, а не права или ссылки на воспроизведение.
