package eu.studio742.imago.core.data

import kotlinx.coroutines.flow.Flow
import eu.studio742.imago.core.composition.BrandKit
import eu.studio742.imago.core.composition.CompositionProject
import eu.studio742.imago.core.composition.CompositionTemplate

interface CompositionRepository {
    fun observeAll(): Flow<List<CompositionProject>>
    suspend fun get(id: String): CompositionProject?
    suspend fun save(project: CompositionProject)
    suspend fun delete(id: String)
    /** A copy with the name [name] gives the original — written by the caller, in the app language. */
    suspend fun duplicate(id: String, name: suspend (original: String) -> String): CompositionProject
}

interface CompositionTemplateRepository {
    fun observeAll(): Flow<List<CompositionTemplate>>
    suspend fun get(id: String): CompositionTemplate?
    suspend fun save(template: CompositionTemplate)
    suspend fun delete(id: String)
}

interface BrandKitRepository {
    fun observe(): Flow<BrandKit?>
    suspend fun save(brandKit: BrandKit)
}
