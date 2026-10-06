import groovy.json.JsonSlurper

/*
 * The Immich client, in pure Kotlin: compiled by Android and desktop. The contract is generated
 * from the OpenAPI specifications in open-api/, at the repository root — which is why the paths are
 * relative to this module and not to each build's root.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("core/immich"))

kotlin { jvmToolchain(17) }

val openApi = layout.projectDirectory.dir("../../open-api")
val minimumOpenApiSpec = openApi.file("immich-openapi-specs-v2.6.0.json").asFile
val validatedOpenApiSpecs = listOf(
    openApi.file("immich-openapi-specs-v2.6.3.json").asFile,
    openApi.file("immich-openapi-specs-v3.1.0.json").asFile,
)
val generatedContractDir = layout.buildDirectory.dir("generated/source/immichContract/kotlin")

val generateImmichContract by tasks.registering {
    inputs.files(listOf(minimumOpenApiSpec) + validatedOpenApiSpecs)
    outputs.dir(generatedContractDir)

    doLast {
        @Suppress("UNCHECKED_CAST")
        fun readSpec(file: File) = JsonSlurper().parse(file) as Map<String, Any?>
        val document = readSpec(minimumOpenApiSpec)
        val validatedDocuments = validatedOpenApiSpecs.map(::readSpec)

        fun pathsOf(spec: Map<String, Any?>) =
            spec["paths"] as Map<String, Map<String, Map<String, Any?>>>
        fun componentsOf(spec: Map<String, Any?>) = spec["components"] as Map<String, Any?>

        val paths = pathsOf(document)
        val components = componentsOf(document)
        val schemes = components["securitySchemes"] as Map<String, Map<String, Any?>>
        val servers = document["servers"] as List<Map<String, String>>

        /**
         * The value this contract uses to write "descending".
         *
         * It comes from the enum and not from a hand-written constant: the client sent "DESC" and
         * the server answered 400 asking for lowercase, and nobody noticed because reading the
         * months fell back to the local catalogue, which masked the failure.
         */
        fun assetOrderDescFor(spec: Map<String, Any?>): String {
            val schemas = componentsOf(spec)["schemas"] as Map<String, Map<String, Any?>>
            val values = schemas.getValue("AssetOrder")["enum"] as List<String>
            return values.first { it.equals("desc", ignoreCase = true) }
        }

        fun operationFor(spec: Map<String, Any?>, operationId: String): Pair<String, String> =
            pathsOf(spec).entries.firstNotNullOf { (path, methods) ->
                methods.entries.firstOrNull { it.value["operationId"] == operationId }
                    ?.let { (method, _) -> method to path }
            }

        val operationIds = listOf(
            "getServerVersion",
            "pingServer",
            "getMyUser",
            "searchAssets",
            "getAllAlbums",
            "getTimeBuckets",
            "getTimeBucket",
            "viewAsset",
            "playAssetVideo",
            "downloadAsset",
            "uploadAsset",
            "createStack",
            "getAssetInfo",
            "updateAsset",
            "deleteAssets",
        )
        validatedDocuments.forEach { validatedDocument ->
            operationIds.forEach { operationId ->
                check(operationFor(document, operationId) == operationFor(validatedDocument, operationId)) {
                    "Immich operation $operationId differs between validated contracts"
                }
            }
        }

        validatedDocuments.forEach { validatedDocument ->
            val validatedComponents = componentsOf(validatedDocument)
            val validatedSchemes = validatedComponents["securitySchemes"] as Map<String, Map<String, Any?>>
            check(schemes.getValue("api_key")["name"] == validatedSchemes.getValue("api_key")["name"])
        }

        @Suppress("UNCHECKED_CAST")
        val schemas = components["schemas"] as Map<String, Map<String, Any?>>
        fun propertiesOf(schema: String): Set<String> =
            (schemas.getValue(schema).getValue("properties") as Map<String, Any?>).keys
        check(propertiesOf("MetadataSearchDto").containsAll(setOf("page", "size", "type", "isFavorite", "withStacked")))
        check(propertiesOf("SearchAssetResponseDto").containsAll(setOf("items", "nextPage")))
        check(
            propertiesOf("AssetResponseDto").containsAll(
                setOf("id", "originalFileName", "fileCreatedAt", "localDateTime", "width", "height", "isFavorite", "isEdited", "type"),
            ),
        )
        check(
            propertiesOf("AssetMediaCreateDto").containsAll(
                setOf("assetData", "deviceAssetId", "deviceId", "fileCreatedAt", "fileModifiedAt", "filename"),
            ),
        )
        check(propertiesOf("AssetMediaResponseDto").containsAll(setOf("id", "status")))
        check(propertiesOf("StackCreateDto").contains("assetIds"))
        // The photo detail: the EXIF strip, the favourite and the caption used as the title. The
        // caption is read-only — Immich does not expose renaming in any validated version.
        check(propertiesOf("AssetResponseDto").contains("exifInfo"))
        check(
            propertiesOf("ExifResponseDto").containsAll(
                setOf("fNumber", "exposureTime", "iso", "focalLength", "make", "model", "lensModel", "description"),
            ),
        )
        check(propertiesOf("UpdateAssetDto").contains("isFavorite"))
        check(propertiesOf("AssetBulkDeleteDto").containsAll(setOf("ids", "force")))
        check(
            propertiesOf("AlbumResponseDto").containsAll(
                setOf("id", "albumName", "albumThumbnailAssetId", "assetCount", "description", "shared"),
            ),
        )
        check(propertiesOf("TimeBucketsResponseDto").containsAll(setOf("timeBucket", "count")))
        validatedDocuments.forEach { validatedDocument ->
            @Suppress("UNCHECKED_CAST")
            val validatedSchemas = componentsOf(validatedDocument)["schemas"] as Map<String, Map<String, Any?>>
            fun validatedProperties(schema: String) =
                (validatedSchemas.getValue(schema).getValue("properties") as Map<String, Any?>).keys
            check(
                validatedProperties("AssetMediaCreateDto").containsAll(
                    setOf("assetData", "fileCreatedAt", "fileModifiedAt", "filename"),
                ),
            )
            check(validatedProperties("AssetMediaResponseDto").containsAll(setOf("id", "status")))
            check(validatedProperties("StackCreateDto").contains("assetIds"))
            check(
                validatedProperties("AlbumResponseDto").containsAll(
                    setOf("id", "albumName", "albumThumbnailAssetId", "assetCount", "description", "shared"),
                ),
            )
            check(validatedProperties("TimeBucketsResponseDto").containsAll(setOf("timeBucket", "count")))
            check(
                validatedProperties("ExifResponseDto").containsAll(
                    setOf("fNumber", "exposureTime", "iso", "focalLength", "make", "model", "lensModel"),
                ),
            )
            check(assetOrderDescFor(document) == assetOrderDescFor(validatedDocument)) {
                "Immich AssetOrder differs between validated contracts"
            }
            check(validatedProperties("UpdateAssetDto").contains("isFavorite"))
            check(validatedProperties("AssetBulkDeleteDto").containsAll(setOf("ids", "force")))
        }

        fun pathFor(operationId: String) = operationFor(document, operationId).second

        /**
         * The API key permission each operation asks for, or null if it is public.
         *
         * It comes from the specifications for the same reason as the paths: it is what the app shows
         * whoever creates the key, and a hand-written list would fall behind the day a new endpoint
         * was used — the key created from the list would fail with 403 without anyone knowing why.
         */
        fun permissionFor(spec: Map<String, Any?>, operationId: String): String? =
            pathsOf(spec).values.firstNotNullOf { methods ->
                methods.values.firstOrNull { it["operationId"] == operationId }
            }["x-immich-permission"] as String?

        validatedDocuments.forEach { validatedDocument ->
            operationIds.forEach { operationId ->
                check(permissionFor(document, operationId) == permissionFor(validatedDocument, operationId)) {
                    "Immich permission for $operationId differs between validated contracts"
                }
            }
        }
        val requiredPermissions = operationIds.mapNotNull { permissionFor(document, it) }.distinct()

        val packageDir = generatedContractDir.get().asFile
            .resolve("eu/studio742/imago/core/immich/generated")
        packageDir.mkdirs()
        packageDir.resolve("ImmichContract.kt").writeText(
            """
            |// Generated from the Immich v2.6.0 minimum contract and checked against v2.6.3 and v3.1.0. Do not edit.
            |package eu.studio742.imago.core.immich.generated
            |
            |internal object ImmichContract {
            |    const val API_BASE = "${servers.first()["url"]}"
            |    const val API_KEY_HEADER = "${schemes.getValue("api_key")["name"]}"
            |    const val GET_SERVER_VERSION = "${pathFor("getServerVersion")}" 
            |    const val PING_SERVER = "${pathFor("pingServer")}"
            |    const val GET_CURRENT_USER = "${pathFor("getMyUser")}" 
            |    const val SEARCH_ASSETS = "${pathFor("searchAssets")}" 
            |    const val GET_ALBUMS = "${pathFor("getAllAlbums")}" 
            |    const val GET_TIME_BUCKETS = "${pathFor("getTimeBuckets")}" 
            |    const val GET_TIME_BUCKET = "${pathFor("getTimeBucket")}"
            |    const val ASSET_ORDER_DESC = "${assetOrderDescFor(document)}" 
            |    const val VIEW_ASSET = "${pathFor("viewAsset")}" 
            |    const val PLAY_ASSET_VIDEO = "${pathFor("playAssetVideo")}" 
            |    const val DOWNLOAD_ASSET = "${pathFor("downloadAsset")}" 
            |    const val UPLOAD_ASSET = "${pathFor("uploadAsset")}" 
            |    const val CREATE_STACK = "${pathFor("createStack")}"
            |    const val GET_ASSET_INFO = "${pathFor("getAssetInfo")}"
            |    const val UPDATE_ASSET = "${pathFor("updateAsset")}"
            |    const val DELETE_ASSETS = "${pathFor("deleteAssets")}"
            |}
            |
            |/** The permissions an API key needs for every endpoint the app uses. */
            |object ImmichKeyPermissions {
            |    val REQUIRED: List<String> = listOf(${requiredPermissions.joinToString { "\"$it\"" }})
            |}
            |""".trimMargin(),
        )
    }
}

sourceSets.main { kotlin.srcDir(generateImmichContract) }

dependencies {
    implementation(project(":core:model"))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
