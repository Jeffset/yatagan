package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.util.PsiModificationTracker
import com.yandex.yatagan.core.graph.impl.Options
import com.yandex.yatagan.core.model.ComponentModel
import com.yandex.yatagan.core.model.impl.ComponentModel
import com.yandex.yatagan.intellij.lang.extra.AnalysisScope
import com.yandex.yatagan.intellij.lang.extra.ContextProvider
import com.yandex.yatagan.intellij.lang.extra.ComponentModelRef
import com.yandex.yatagan.lang.LangModelFactory
import com.yandex.yatagan.lang.TypeDeclaration
import com.yandex.yatagan.lang.common.LangOptions
import com.yandex.yatagan.lang.common.scope.LexicalScopeBase
import com.yandex.yatagan.lang.scope.CachingMetaFactory
import com.yandex.yatagan.lang.scope.LexicalScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.system.measureTimeMillis

class IJLexicalScope(
    private val project: Project,
) : LexicalScopeBase(), CachingMetaFactory, Disposable {
    private var isAnalyzing = false
    private val contextProvider = ContextProviderImpl()
    private val dispatcher: ExecutorCoroutineDispatcher = run {
        val threadFactory = ThreadFactory { runnable ->
            Thread(runnable).apply {
                name = "Yatagan-Analysis"
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                // TODO: Uncaught exception handler?
            }
        }
        Executors.newSingleThreadExecutor(threadFactory).asCoroutineDispatcher()
    }

    init {
        ext[CachingMetaFactory] = this
        ext[ProcessingUtils] = ProcessingUtils(project)
        ext[LangModelFactory] = IjModelFactoryImpl(this)

        ext[Options] = Options(
            allConditionsLazy = false,
        )
        ext[LangOptions] = LangOptions(
            daggerCompatibilityMode = false,
        )
        ext[ContextProvider] = contextProvider

//        val uastLanguages = UastMetaLanguage.getRegisteredLanguages()
//        PsiModificationTracker.getInstance(project).forLanguages { it in uastLanguages }

        project.messageBus.connect(this).subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener {
            // TODO: [Optimize] Drop only the affected caches
            check(!isAnalyzing) { "Psi modified when analyzing" }

            val time = measureTimeMillis {
                factories.forEach {
                    it.onPsiModification()
                    // TODO: is it okay to do it on every psi mod?
                    it.cleanOnStaleDependenciesCache()
                }
            }
            LOGGER.info("Caches maintenance on psi-mod took $time ms. ")
        })
    }

    private val factories = arrayListOf<PsiAwareCachingFactory<*, *>>()

    fun createRefPossiblyInvalid(element: PsiClass): ComponentModelRef {
        val manager = SmartPointerManager.getInstance(project)
        return ComponentModelRefImpl(pointer = manager.createSmartPsiElementPointer(element))
    }

    suspend fun <R> analyze(context: PsiElement, block: AnalysisScope.() -> R): R {
        return withContext(dispatcher) {
            readActionBlocking {
                check(!isAnalyzing) { "Nested analyze calls are forbidden" }
                try {
                    isAnalyzing = true
                    contextProvider.contextMutable = context
                    factories.forEach { it.beginAnalyze() }
                    block.invoke(AnalysisScopeImpl())
                } finally {
                    try {
                        factories.forEach { it.endAnalyze() }
                    } catch (e: Throwable) {
                        factories.forEach { it.reset() }
                        throw e
                    } finally {
                        contextProvider.contextMutable = null
                        isAnalyzing = false
                    }
                }
            }
        }
    }

    override fun <T, R> createFactory(delegate: LexicalScope.(T) -> R): LexicalScope.(T) -> R {
        checkUsage()
        return PsiAwareCachingFactory(delegate).also { factories += it }
    }

    // TODO: Check that analysis scope is not leaked (used beyond `analyze{}` call)
    private inner class AnalysisScopeImpl : AnalysisScope {
        override val lexicalScope: LexicalScope
            get() = this@IJLexicalScope

        override fun getTypeDeclaration(element: PsiClass): TypeDeclaration {
            checkUsage()
            return IJTypeImpl(Utils.factory.createType(element)).declaration
        }

        override fun ComponentModelRef.get(): ComponentModel? {
            require(this is ComponentModelRefImpl)
            check(owner == this@IJLexicalScope) {
                // TODO: Is this a really necessary requirement?
                "Can't dereference a lang ref in a different lexical scope than it was obtained"
            }
            return source?.let { ComponentModel(getTypeDeclaration(it), strict = true) }
        }

        override fun ComponentModel.asRef(): ComponentModelRef {
            return createRefPossiblyInvalid(langModel as PsiClass)
        }
    }

    private class ContextProviderImpl : ContextProvider {
        var contextMutable: PsiElement? = null

        override val context: PsiElement
            get() = checkNotNull(contextMutable)
    }

    private inner class ComponentModelRefImpl(
        private val pointer: SmartPsiElementPointer<PsiClass>,
    ) : ComponentModelRef {
        val owner: IJLexicalScope get() = this@IJLexicalScope

        override val source: PsiClass?
            get() = pointer.element?.takeIf { it.isValid }

        override fun equals(other: Any?): Boolean =
            this === other || (other is ComponentModelRefImpl
                && other.owner == this.owner
                && other.pointer == this.pointer)

        override fun hashCode(): Int {
            return 31 * owner.hashCode() + pointer.hashCode()
        }
    }

    private inner class PsiAwareCachingFactory<T, R>(
        private val delegate: LexicalScope.(T) -> R,
    ): (LexicalScope, T) -> R {
        // Hard cache, valid inside { beginAnalyze(); endAnalyze() }
        private val analyzeCache = hashMapOf<T, R>()

        // Cache, that is dropped on any PSI modification
        private val dropOnWorldChangeCache = mutableListOf<Pair<T, R>>()

        // Cache, that is dropped when dependencies become invalid
        private val dropOnDependencyChangeCache = mutableListOf<Triple<T, R, List<Pair<PsiFile, Long>>>>()

        fun beginAnalyze() {
            // Ensure no stale
            cleanOnStaleDependenciesCache()

            // Populate analyzeCache with the data that survived in the caches
            for ((input, value, _) in dropOnDependencyChangeCache) analyzeCache[input] = value
            analyzeCache.putAll(dropOnWorldChangeCache)

            // Clear all the caches
            dropOnDependencyChangeCache.clear()
            dropOnWorldChangeCache.clear()
        }

        fun endAnalyze() {
            // Transfer all the objects inside the analyzeCache to the corresponding caches
            for ((input, instance) in analyzeCache) {
                if (instance is HasDependencies) run smart@ {
                    val dependencies = instance.dependencies
                    val psiFiles = dependencies.map { dep ->
                        when(dep) {
                            is Dependency.Psi -> {
                                val sourcePsi: PsiFile = if (!dep.psi.isPhysical) {
                                    dep.psi.virtualFile?.let { Utils.psiManager.findFile(it) } ?: run {
                                        // No physical file - no caching (AT ALL)
                                        return@smart
                                    }
                                } else dep.psi
                                Pair(sourcePsi, sourcePsi.modificationStamp)
                            }
                            Dependency.World -> {
                                dropOnWorldChangeCache += Pair(input, instance)
                                return@smart
                            }
                        }
                    }
                    dropOnDependencyChangeCache += Triple(input, instance, psiFiles)
                } else {
                    dropOnWorldChangeCache += Pair(input, instance)
                }
            }

            // Clear analyzeCache
            analyzeCache.clear()
        }

        override fun invoke(lexicalScope: LexicalScope, input: T): R {
            checkUsage()
            return analyzeCache.getOrPut(input) {
                with(delegate) { invoke(lexicalScope, input) }
            }
        }

        fun onPsiModification() {
            dropOnWorldChangeCache.clear()
        }

        fun cleanOnStaleDependenciesCache() {
            dropOnDependencyChangeCache.removeAll { (_: T, _: R, dependencies) ->
                dependencies.any { (psi, stamp) -> !psi.isValid || psi.modificationStamp != stamp }
            }
        }

        fun reset() {
            analyzeCache.clear()
            dropOnWorldChangeCache.clear()
            dropOnDependencyChangeCache.clear()
        }
    }

    override fun dispose() {
        check(!isAnalyzing) { "Dispose while analyzing" }
        factories.forEach{ it.reset() }
    }

    private fun checkUsage() {
        check(isAnalyzing) { "Lexical scope usage is forbidden outside of analyze { }" }
    }

    companion object {
        private val LOGGER = Logger.getInstance(IJLexicalScope::class.java)
    }
}
