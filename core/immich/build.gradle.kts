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
            "updateAssets",
            "deleteAssets",
            "createAlbum",
            "addAssetsToAlbum",
            "removeAssetFromAlbum",
            "updateAlbumInfo",
            "deleteAlbum",
            "restoreAssets",
            "emptyTrash",
            "getServerConfig",
            "searchSmart",
            "getServerFeatures",
            "getMyApiKey",
            "getAssetEdits",
            "editAsset",
            "removeAssetEdits",
            "getAllPeople",
            "getPersonThumbnail",
        )

        /**
         * The operations the app works without. A self-hosting person may well refuse a key that can
         * write to the server; with only the permissions of the other operations IMAGO still edits and
         * exports to the device, and each of these answers with the permission it lacks.
         */
        val allPermission = "all"
        val optionalOperationIds = setOf(
            "getAllAlbums", "uploadAsset", "createStack", "updateAsset", "updateAssets", "deleteAssets",
            "createAlbum", "addAssetsToAlbum", "removeAssetFromAlbum", "updateAlbumInfo", "deleteAlbum",
            "restoreAssets", "emptyTrash",
            "getAssetEdits", "editAsset", "removeAssetEdits",
            "getAllPeople", "getPersonThumbnail",
        )
        check(operationIds.containsAll(optionalOperationIds))
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
        check(propertiesOf("AssetBulkUpdateDto").containsAll(setOf("ids", "isFavorite")))
        check(propertiesOf("AssetBulkDeleteDto").containsAll(setOf("ids", "force")))
        // Managing albums. Who owns one is read from `ownerId` up to v2 and from the first
        // `albumUsers` entry, with the role "owner", from v3: the client reads both.
        check(propertiesOf("CreateAlbumDto").containsAll(setOf("albumName", "assetIds")))
        check(propertiesOf("BulkIdsDto").contains("ids"))
        check(propertiesOf("BulkIdResponseDto").containsAll(setOf("id", "success", "error")))
        check(propertiesOf("UpdateAlbumDto").contains("albumName"))
        check(propertiesOf("AlbumResponseDto").contains("albumUsers"))
        check(propertiesOf("AlbumUserResponseDto").containsAll(setOf("role", "user")))
        // The trash: listed through the search, restored and emptied by its own endpoints.
        check(propertiesOf("MetadataSearchDto").containsAll(setOf("withDeleted", "trashedAfter")))
        check(propertiesOf("TrashResponseDto").contains("count"))
        check(propertiesOf("ServerConfigDto").contains("trashDays"))
        // Searching by what is in the photo, when the server has it on.
        check(propertiesOf("SmartSearchDto").containsAll(setOf("query", "page", "size", "albumIds", "isFavorite", "takenAfter", "takenBefore", "language")))
        check(propertiesOf("ServerFeaturesDto").contains("smartSearch"))
        // The people the server recognises, and the photos of each one.
        check(propertiesOf("PeopleResponseDto").containsAll(setOf("people", "hasNextPage")))
        check(propertiesOf("PersonResponseDto").containsAll(setOf("id", "name", "isHidden")))
        check(propertiesOf("MetadataSearchDto").contains("personIds"))
        check(
            propertiesOf("AlbumResponseDto").containsAll(
                setOf("id", "albumName", "albumThumbnailAssetId", "assetCount", "description", "shared"),
            ),
        )
        check(propertiesOf("TimeBucketsResponseDto").containsAll(setOf("timeBucket", "count")))
        // The geometry mirrored to Immich's own edits. The crop is in pixels of the image already
        // turned by its EXIF orientation, and the server only accepts it when it fits in
        // exifImageWidth x exifImageHeight swapped by that orientation: those three fields are how
        // the client knows the size the server checks against.
        fun checkEditContract(spec: Map<String, Any?>) {
            @Suppress("UNCHECKED_CAST")
            val specSchemas = componentsOf(spec)["schemas"] as Map<String, Map<String, Any?>>
            fun properties(schema: String) = (specSchemas.getValue(schema).getValue("properties") as Map<String, Any?>).keys
            check(properties("AssetEditsCreateDto").contains("edits"))
            check(properties("AssetEditsResponseDto").contains("edits"))
            check(properties("AssetEditActionItemDto").containsAll(setOf("action", "parameters")))
            check(properties("CropParameters").containsAll(setOf("x", "y", "width", "height")))
            check(properties("RotateParameters").contains("angle"))
            check(properties("MirrorParameters").contains("axis"))
            check(specSchemas.getValue("AssetEditAction")["enum"] == listOf("crop", "rotate", "mirror")) {
                "Immich edit actions changed"
            }
            check(specSchemas.getValue("MirrorAxis")["enum"] == listOf("horizontal", "vertical")) {
                "Immich mirror axes changed"
            }
            check(properties("ExifResponseDto").containsAll(setOf("exifImageWidth", "exifImageHeight", "orientation")))
        }
        checkEditContract(document)
        validatedDocuments.forEach(::checkEditContract)
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
            check(validatedProperties("AssetBulkUpdateDto").containsAll(setOf("ids", "isFavorite")))
            check(validatedProperties("CreateAlbumDto").containsAll(setOf("albumName", "assetIds")))
            check(validatedProperties("BulkIdsDto").contains("ids"))
            check(validatedProperties("BulkIdResponseDto").containsAll(setOf("id", "success", "error")))
            check(validatedProperties("UpdateAlbumDto").contains("albumName"))
            check(validatedProperties("AlbumResponseDto").contains("albumUsers"))
            check(validatedProperties("AlbumUserResponseDto").containsAll(setOf("role", "user")))
            check(validatedProperties("MetadataSearchDto").containsAll(setOf("withDeleted", "trashedAfter")))
            check(validatedProperties("ServerConfigDto").contains("trashDays"))
            check(validatedProperties("SmartSearchDto").containsAll(setOf("query", "page", "size", "albumIds", "isFavorite", "takenAfter", "takenBefore", "language")))
            check(validatedProperties("ServerFeaturesDto").contains("smartSearch"))
            check(validatedProperties("AssetBulkDeleteDto").containsAll(setOf("ids", "force")))
            check(validatedProperties("PeopleResponseDto").containsAll(setOf("people", "hasNextPage")))
            check(validatedProperties("PersonResponseDto").containsAll(setOf("id", "name", "isHidden")))
            check(validatedProperties("MetadataSearchDto").contains("personIds"))
        }

        fun pathFor(operationId: String) = operationFor(document, operationId).second
        check(setOf("getAssetEdits", "editAsset", "removeAssetEdits").map(::pathFor).distinct().size == 1) {
            "Immich edit operations no longer share a path"
        }

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
        val requiredPermissions = operationIds.filterNot { it in optionalOperationIds }
            .mapNotNull { permissionFor(document, it) }.distinct()
        val optionalPermissions = optionalOperationIds.mapNotNull { permissionFor(document, it) }
            .distinct().filterNot { it in requiredPermissions }
        // Reading the key's own permissions must not itself need one, or a key without it could not
        // be told what it lacks.
        check(permissionFor(document, "getMyApiKey") == null)

        // The schema is named APIKeyResponseDto in v2 and ApiKeyResponseDto in v3: it is reached
        // through the response, not by name.
        fun responseSchemaOf(spec: Map<String, Any?>, operationId: String): Map<String, Any?> {
            val operation = pathsOf(spec).values.firstNotNullOf { methods ->
                methods.values.firstOrNull { it["operationId"] == operationId }
            }
            @Suppress("UNCHECKED_CAST")
            val content = ((operation["responses"] as Map<String, Map<String, Any?>>).getValue("200")["content"]
                as Map<String, Map<String, Map<String, String>>>)
            val name = content.getValue("application/json").getValue("schema").getValue("\$ref").substringAfterLast('/')
            @Suppress("UNCHECKED_CAST")
            return (componentsOf(spec)["schemas"] as Map<String, Map<String, Any?>>).getValue(name)
        }
        (listOf(document) + validatedDocuments).forEach { spec ->
            @Suppress("UNCHECKED_CAST")
            val properties = responseSchemaOf(spec, "getMyApiKey").getValue("properties") as Map<String, Any?>
            check("permissions" in properties) { "Immich no longer lists the key's permissions" }
            @Suppress("UNCHECKED_CAST")
            val permissionValues = (componentsOf(spec)["schemas"] as Map<String, Map<String, Any?>>)
                .getValue("Permission")["enum"] as List<String>
            check(allPermission in permissionValues) { "Immich no longer has the \"$allPermission\" permission" }
        }

        fun permissionConstant(operationId: String): String? = permissionFor(document, operationId)?.let { permission ->
            val name = operationId.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
            "|    const val $name = \"$permission\""
        }

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
            |    const val UPDATE_ASSETS = "${pathFor("updateAssets")}"
            |    const val DELETE_ASSETS = "${pathFor("deleteAssets")}"
            |    const val CREATE_ALBUM = "${pathFor("createAlbum")}"
            |    const val ADD_ASSETS_TO_ALBUM = "${pathFor("addAssetsToAlbum")}"
            |    const val REMOVE_ASSET_FROM_ALBUM = "${pathFor("removeAssetFromAlbum")}"
            |    const val UPDATE_ALBUM_INFO = "${pathFor("updateAlbumInfo")}"
            |    const val DELETE_ALBUM = "${pathFor("deleteAlbum")}"
            |    const val RESTORE_ASSETS = "${pathFor("restoreAssets")}"
            |    const val EMPTY_TRASH = "${pathFor("emptyTrash")}"
            |    const val GET_SERVER_CONFIG = "${pathFor("getServerConfig")}"
            |    const val SEARCH_SMART = "${pathFor("searchSmart")}"
            |    const val GET_SERVER_FEATURES = "${pathFor("getServerFeatures")}"
            |    const val GET_MY_API_KEY = "${pathFor("getMyApiKey")}"
            |    const val ASSET_EDITS = "${pathFor("editAsset")}"
            |    const val GET_ALL_PEOPLE = "${pathFor("getAllPeople")}"
            |    const val GET_PERSON_THUMBNAIL = "${pathFor("getPersonThumbnail")}"
            |}
            |
            |/** The API key permissions the endpoints the app uses ask for. */
            |object ImmichKeyPermissions {
            |    /** Grants every permission, including the ones a later Immich adds. */
            |    const val ALL = "$allPermission"
            |
            |    /** Without these there is no library to show or original to edit. */
            |    val REQUIRED: List<String> = listOf(${requiredPermissions.joinToString { "\"$it\"" }})
            |
            |    /** Each one only turns off what asks for it. */
            |    val OPTIONAL: List<String> = listOf(${optionalPermissions.joinToString { "\"$it\"" }})
            |
            |    // The permission of each operation, named after it.
            ${operationIds.mapNotNull(::permissionConstant).joinToString("\n")}
            |
            |    fun grants(granted: Collection<String>, permission: String): Boolean = ALL in granted || permission in granted
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
