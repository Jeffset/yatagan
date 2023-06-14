package com.yandex.yatagan.intellij.lang

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiType
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analyzer.ModuleInfo
import org.jetbrains.kotlin.analyzer.ResolverForModule
import org.jetbrains.kotlin.analyzer.ResolverForProject
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.caches.resolve.KotlinCacheService
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.container.ComponentProvider
import org.jetbrains.kotlin.container.get
import org.jetbrains.kotlin.descriptors.TypeAliasDescriptor
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.base.projectStructure.moduleInfo
import org.jetbrains.kotlin.idea.base.util.module
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.idea.util.getResolutionScope
import org.jetbrains.kotlin.idea.util.resolveToKotlinType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtClassLiteralExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStubbedPsiUtil
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtUserType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.QualifiedExpressionResolver
import org.jetbrains.kotlin.resolve.TypeResolutionContext
import org.jetbrains.kotlin.resolve.constants.KClassValue
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.classId
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.resolve.lazy.ForceResolveUtil
import org.jetbrains.kotlin.resolve.lazy.ResolveSession
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.SimpleType
import org.jetbrains.kotlin.types.TypeProjection
import org.jetbrains.kotlin.types.replace
import org.jetbrains.kotlin.types.typeUtil.substitute
import org.jetbrains.kotlin.types.withAbbreviation

private var utils: ProcessingUtils? = null

internal val Utils: ProcessingUtils get() = checkNotNull(utils) {
    "Not reached: utils are used before set/after cleared"
}

class ProcessingUtils(
    private val project: Project,
) : AutoCloseable {
    private val qualifiedExpressionResolver = QualifiedExpressionResolver(LanguageVersionSettingsImpl.DEFAULT)

    private fun KtUserType.referencedFqName(): FqName? {
        val allTypes = generateSequence(this) { it.qualifier }.toList().asReversed()
        val allQualifiers = allTypes.map { it.referencedName ?: return null }

        return FqName.fromSegments(allQualifiers)
    }

    fun evaluateExpression(
        expression: KtExpression,
        expectedType: PsiType,
        context: PsiElement,
    ): KotlinConstantValue? {
        val facade: ResolutionFacade = KotlinCacheService.getInstance(project)
            .getResolutionFacadeByModuleInfo(
                moduleInfo = context.moduleInfo,
                platform = context.module?.platform ?: JvmPlatforms.unspecifiedJvmPlatform,
            )!!
        val resolver: ResolverForProject<out ModuleInfo> = facade.getResolverForProject()
        val moduleResolver: ResolverForModule = resolver.resolverForModuleDescriptor(facade.moduleDescriptor)
        val componentProvider: ComponentProvider = moduleResolver.componentProvider
        val resolveSession: ResolveSession = componentProvider.get()
        val bindingTrace: BindingTrace = componentProvider.get()
        val constantExpressionEvaluator: ConstantExpressionEvaluator = componentProvider.get()

        return expression.let {
            if (it is KtClassLiteralExpression && it.receiverExpression != null) {
//                val parent = KtStubbedPsiUtil.getPsiOrStubParent(it, KtPrimaryConstructor::class.java, false)
                val scope = expression.getResolutionScope(bindingTrace.bindingContext, facade)// resolveSession.declarationScopeProvider.getResolutionScopeForDeclaration(parent!!)
                val result = qualifiedExpressionResolver
                    .resolveDescriptorForDoubleColonLHS(it.receiverExpression!!, scope, bindingTrace, false)
                val classifier = result.classifierDescriptor ?: return null
                val typeResolutionContext = TypeResolutionContext(scope, bindingTrace, true, true, false)
                val possiblyBareType = resolveSession.typeResolver
                    .resolveTypeForClassifier(typeResolutionContext, classifier, result, it, Annotations.EMPTY)
                var actualType = if (possiblyBareType.isBare)
                    possiblyBareType.bareTypeConstructor.declarationDescriptor!!.defaultType
                else possiblyBareType.actualType
                var arrayDimension = 0
                while (KotlinBuiltIns.isArray(actualType)) {
                    actualType = actualType.arguments.single().type
                    arrayDimension += 1
                }
                KClassValue(actualType.constructor.declarationDescriptor.classId!!, arrayDimension)
            } else {
//                val bodyResolver: BodyResolver = componentProvider.get()
                val expectedKType = expectedType.resolveToKotlinType(facade)
//                val declarationScopeProvider: DeclarationScopeProvider = componentProvider.get()
//                val topDownAnalysisContext = TopDownAnalysisContext(TopDownAnalysisMode.TopLevelDeclarations, DataFlowInfo.EMPTY, declarationScopeProvider)

                constantExpressionEvaluator.evaluateExpression(it, bindingTrace)?.toConstantValue(expectedKType) ?: run {
//                    val parent = KtStubbedPsiUtil
//                        .getPsiOrStubParent(expression, KtPrimaryConstructor::class.java, false)
//                    val scope = expression.getResolutionScope(bindingTrace.bindingContext, facade)

                    qualifiedExpressionResolver
                        .resolvePackageHeader(expression.containingKtFile.packageDirective!!,
                            facade.moduleDescriptor, bindingTrace)
                    facade.analyze(expression, BodyResolveMode.PARTIAL_NO_ADDITIONAL)
//                    bodyResolver.resolveConstructorParameterDefaultValues(
//                        topDownAnalysisContext.outerDataFlowInfo, bindingTrace,
//                        parent, (scope.ownerDescriptor as ClassDescriptor).constructors.first(), scope,
//                        resolveSession.inferenceSession
//                    )
                    constantExpressionEvaluator.evaluateExpression(it, bindingTrace)?.toConstantValue(expectedKType)
                }
            }
        }
    }

    fun lightResolveAnnotationFqName(annotationEntry: KtAnnotationEntry, resolveScope: GlobalSearchScope): PsiClass? {
        val annotationTypeElement = annotationEntry.typeReference?.typeElement as? KtUserType
        val referencedName = annotationTypeElement?.referencedFqName() ?: return null

        // FIXME what happens with aliased imports? They are correctly reported by the annotation index
        if (referencedName.isRoot) return null

        if (!referencedName.parent().isRoot) {
            // we assume here that the annotation is used by its fully-qualified name
            referencedName.findPsiClassKotlinAware(project, resolveScope)?.let { return it }
        }

        val candidates = getCandidatesFromImports(annotationEntry.containingKtFile, referencedName.shortName())

        return candidates.fromExplicitImports.resolveToSingleName(resolveScope)
            ?: candidates.fromSamePackage.resolveToSingleName(resolveScope)
            ?: candidates.fromStarImports.resolveToSingleName(resolveScope)
    }

    private fun KotlinType.expandNonRecursively(): KotlinType =
        (constructor.declarationDescriptor as? TypeAliasDescriptor)?.expandedType?.withAbbreviation(this as SimpleType)
            ?: this

    // TODO: Is this the most efficient way?
    private fun KotlinType.expand(): KotlinType =
        replace(arguments.map { it.expand() }).expandNonRecursively()

    private fun TypeProjection.expand(): TypeProjection {
        val expandedType = type.expand()
        return if (expandedType == type) this else substitute { expandedType }
    }

    private fun KtTypeReference.lookup(trace: BindingTrace): KotlinType? =
        trace.get(BindingContext.ABBREVIATED_TYPE, this)?.expand() ?: trace.get(BindingContext.TYPE, this)

    fun resolveKotlinType(typeReference: KtTypeReference, context: PsiElement): KotlinType {
        val facade: ResolutionFacade = KotlinCacheService.getInstance(project)
            .getResolutionFacadeByModuleInfo(
                moduleInfo = context.moduleInfo,
                platform = context.module?.platform ?: JvmPlatforms.unspecifiedJvmPlatform,
            )!!
        val resolver: ResolverForProject<out ModuleInfo> = facade.getResolverForProject()
        val moduleResolver: ResolverForModule = resolver.resolverForModuleDescriptor(facade.moduleDescriptor)
        val componentProvider: ComponentProvider = moduleResolver.componentProvider
        val resolveSession: ResolveSession = componentProvider.get()
        val bindingTrace: BindingTrace = componentProvider.get()

        typeReference.lookup(bindingTrace)?.let {
            return it
        }
        KtStubbedPsiUtil.getContainingDeclaration(typeReference)?.let { containingDeclaration ->
            resolveSession.resolveToDescriptor(containingDeclaration).let {
                // TODO: only resolve relevant branch.
                ForceResolveUtil.forceResolveAllContents(it)
            }
            // TODO: Fix resolution look up to avoid fallback to file scope.
            typeReference.lookup(bindingTrace)?.let {
                return it
            }
        }
        val scope = resolveSession.fileScopeProvider.getFileResolutionScope(typeReference.containingKtFile)
        return resolveSession.typeResolver.resolveType(scope, typeReference, bindingTrace, false)
    }

    fun resolveAnnotationEntry(annotationEntry: KtAnnotationEntry, context: PsiElement): AnnotationDescriptor? {
        val facade: ResolutionFacade = KotlinCacheService.getInstance(project)
            .getResolutionFacadeByModuleInfo(
                moduleInfo = context.moduleInfo,
                platform = context.module?.platform ?: JvmPlatforms.unspecifiedJvmPlatform,
            )!!
        val resolver: ResolverForProject<out ModuleInfo> = facade.getResolverForProject()
        val moduleResolver: ResolverForModule = resolver.resolverForModuleDescriptor(facade.moduleDescriptor)
        val componentProvider: ComponentProvider = moduleResolver.componentProvider
        val resolveSession: ResolveSession = componentProvider.get()
        val bindingTrace: BindingTrace = componentProvider.get()

        bindingTrace.get(BindingContext.ANNOTATION, annotationEntry)?.let { return it }
        KtStubbedPsiUtil.getContainingDeclaration(annotationEntry)?.let { containingDeclaration ->
            resolveSession.resolveToDescriptor(containingDeclaration).annotations.forEach {}
        } ?: annotationEntry.containingKtFile.let {
            resolveSession.getFileAnnotations(it).forEach {}
        }
        return bindingTrace.get(BindingContext.ANNOTATION, annotationEntry)
    }

    private data class ResolveByImportsCandidates(
        val fromSamePackage: Set<FqName>,
        val fromExplicitImports: Set<FqName>,
        val fromStarImports: Set<FqName>,
    )

    private fun getCandidatesFromImports(file: KtFile, targetName: Name): ResolveByImportsCandidates {
        val starImports = mutableSetOf<FqName>()
        val explicitImports = mutableSetOf<FqName>()

        for (import in file.importDirectives) {
            val importedName = import.importedFqName ?: continue

            if (import.isAllUnder) {
                starImports += importedName.child(targetName)
            } else if (importedName.shortName() == targetName) {
                explicitImports += importedName
            }
        }

        val packageImport = file.packageFqName.child(targetName)

        return ResolveByImportsCandidates(setOf(packageImport), explicitImports, starImports)
    }

    private fun Set<FqName>.resolveToSingleName(resolveScope: GlobalSearchScope): PsiClass? = firstNotNullOfOrNull {
        it.findPsiClassKotlinAware(project, resolveScope)
    }

    private fun annotationActuallyExists(matchingImport: FqName): Boolean {

//        val foundClasses = KotlinFullClassNameIndex[matchingImport.asString(), project, resolveScope]
        println(matchingImport)
        return true// foundClasses.singleOrNull { it.isAnnotation() && it.isTopLevel() } != null
    }

    init {
        utils = this
    }

    override fun close() {
        utils = null
    }
}