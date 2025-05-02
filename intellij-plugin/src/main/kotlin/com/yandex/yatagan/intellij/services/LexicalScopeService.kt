package com.yandex.yatagan.intellij.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.uast.UastMetaLanguage
import com.intellij.util.Processor
import com.intellij.util.Query
import com.yandex.yatagan.intellij.lang.IJLexicalScope
import com.yandex.yatagan.intellij.lang.extra.ComponentModelRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@Service(Service.Level.PROJECT)
internal class LexicalScopeService(
    private val project: Project,
    val coroutineScope: CoroutineScope,
) : Disposable {
    val lexicalScope: IJLexicalScope by lazy {
        IJLexicalScope(project).also {
            Disposer.register(this@LexicalScopeService, it)
        }
    }

    private val modificationTracker = PsiModificationTracker.getInstance(project).forLanguages {
        UastMetaLanguage.findLanguageByID(it.id) != null
    }

    private var cachedComponents: Set<ComponentModelRef> = emptySet()
    private var lastSearchStamp = -1L
    private var activeSearchJob: Job? = null

    private val _searchEventsFlow = MutableSharedFlow<ComponentSearchEvent>(
        replay = 500,
        onBufferOverflow = BufferOverflow.DROP_LATEST,
    )
    val allComponentsSearchFlow: SharedFlow<ComponentSearchEvent>
        get() = _searchEventsFlow

    private val componentAnnotation = CachedValuesManager.getManager(project).createCachedValue {
        val scope = GlobalSearchScope.allScope(project)
        val annotation = runBlocking {
            readAction {
                JavaPsiFacade.getInstance(project).findClass("com.yandex.yatagan.Component", scope)!!
            }
        }
        CachedValueProvider.Result(annotation, annotation)
    }

    init {
        DumbService.getInstance(project).runWhenSmart {
            refreshComponents()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun refreshComponents() {
        if (activeSearchJob?.isActive == true) {
            // Already refreshing
            return
        }

        _searchEventsFlow.resetReplayCache()
        activeSearchJob = coroutineScope.launch {
            _searchEventsFlow.emit(ComponentSearchEvent.SearchStarted)
            val oldComponents = cachedComponents
            try {
                _searchEventsFlow.tryEmit(ComponentSearchEvent.CachedCandidates(
                    candidates = oldComponents,
                ))
                val currentStamp = modificationTracker.modificationCount
                if (currentStamp != lastSearchStamp) {
                    lastSearchStamp = currentStamp
                    // Search again
                    val searchScope: SearchScope = GlobalSearchScope.projectScope(project)
                    val query: Query<PsiClass> = AnnotatedElementsSearch.searchPsiClasses(
                        componentAnnotation.value, searchScope)

                    val newComponents = mutableSetOf<ComponentModelRef>()
                    val processor = Processor<PsiClass> { clazz ->
                        val ref = lexicalScope.createRefPossiblyInvalid(clazz)
                        newComponents.add(ref)
                        if (ref !in oldComponents) {
                            _searchEventsFlow.tryEmit(ComponentSearchEvent.FoundCandidate(ref))
                        }
                        /* continue if */coroutineContext.isActive
                    }
                    query.forEach(processor)
                    cachedComponents = newComponents
                }
            } finally {
                _searchEventsFlow.tryEmit(ComponentSearchEvent.SearchCompleted)
            }
        }
    }

    override fun dispose() {
        // Nothing here for now
    }

    companion object {
        fun getInstance(project: Project): LexicalScopeService =
            project.getService(LexicalScopeService::class.java)
    }

    sealed interface ComponentSearchEvent {
        object SearchStarted : ComponentSearchEvent

        class CachedCandidates(
            val candidates: Collection<ComponentModelRef>
        ) : ComponentSearchEvent

        class FoundCandidate(
            val candidate: ComponentModelRef,
        ) : ComponentSearchEvent

        object SearchCompleted : ComponentSearchEvent
    }
}