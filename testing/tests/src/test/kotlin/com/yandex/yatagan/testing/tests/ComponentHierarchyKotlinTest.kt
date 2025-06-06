/*
 * Copyright 2022 Yandex LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yandex.yatagan.testing.tests

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import javax.inject.Provider

@RunWith(Parameterized::class)
class ComponentHierarchyKotlinTest(
    driverProvider: Provider<CompileTestDriverBase>
) : CompileTestDriver by driverProvider.get() {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun parameters() = compileTestDrivers()
    }

    @Test
    fun `subcomponents - basic case`() {
        givenKotlinSource(
            "test.TestCase", """
            import javax.inject.Inject
            import javax.inject.Scope
            import javax.inject.Singleton
            import com.yandex.yatagan.Component
            import com.yandex.yatagan.Binds
            import com.yandex.yatagan.Module
            import javax.inject.Provider
            import com.yandex.yatagan.Lazy

            @Scope
            annotation class ActivityScoped

            interface MyApplicationManager
            class MyApplicationManagerImpl @Inject constructor() : MyApplicationManager
            
            @Module(subcomponents = [MyActivityComponent::class])
            interface ApplicationModule {
                @Binds
                fun appManager(i: MyApplicationManagerImpl): MyApplicationManager
            }
            
            @Singleton
            @Component(modules = [ApplicationModule::class])
            interface MyApplicationComponent {
                @Component.Builder
                interface Factory {
                    fun create(): MyApplicationComponent 
                }
            }

            @ActivityScoped
            @Component(isRoot = false)
            interface MyActivityComponent {
                val appManager: MyApplicationManager
                val appManagerLazy: Lazy<MyApplicationManager>
                val appManagerProvider: Provider<MyApplicationManager>

                @Component.Builder
                interface Factory {
                    fun create(): MyActivityComponent 
                }
            }
        """
        )

        compileRunAndValidate()
    }

    @Test
    fun `subcomponents - advanced case`() {
        givenKotlinSource(
            "test.TestCase",
            """
            import com.yandex.yatagan.*
            import javax.inject.*

            @Scope annotation class ActivityScoped
            @Scope annotation class FragmentScoped

            interface MyApplicationManager
            class MyApplicationManagerImpl @Inject constructor(
                controller: MyApplicationController,
                @Named("app_id") id: String,
            ) : MyApplicationManager

            interface MyApplicationController
            @Singleton
            class MyApplicationControllerImpl @Inject constructor() : MyApplicationController
            
            @Module(subcomponents = [MyActivityComponent::class])
            interface ApplicationModule {
                @Binds
                fun appManager(i: MyApplicationManagerImpl): MyApplicationManager
                @Binds
                fun controller(i: MyApplicationControllerImpl): MyApplicationController
            }
            
            @Singleton
            @Component(modules = [ApplicationModule::class])
            interface MyApplicationComponent {
                val activityFactory: MyActivityComponent.Factory

                @Component.Builder
                interface Factory {
                    fun create(
                        @BindsInstance @Named("app_id") appId: String,
                    ): MyApplicationComponent
                }
            }
    
            @Module(subcomponents = [MyFragmentComponent::class])
            interface MyActivityModule

            class MyActivityController @Inject constructor(
                appComponent: MyApplicationComponent,
                @Named("app_id") appId: Lazy<String>,
                @Named("activity_id") id: Provider<String>,
            )

            @ActivityScoped
            @Component(isRoot = false, modules = [MyActivityModule::class])
            interface MyActivityComponent {
                val appManager: MyApplicationManager
                val appManagerLazy: Lazy<MyApplicationManager>
                val appManagerProvider: Provider<MyApplicationManager>

                val fragmentFactory: MyFragmentComponent.Factory

                @Component.Builder
                interface Factory {
                    fun create(@BindsInstance @Named("activity_id") id: String): MyActivityComponent 
                }
            }

            @FragmentScoped
            class FragmentController @Inject constructor(
                val activityController: MyActivityController,
            )

            @FragmentScoped
            @Component(isRoot = false)
            interface MyFragmentComponent {
                val appManager: MyApplicationManager
                val appManagerLazy: Lazy<MyApplicationManager>
                val appManagerProvider: Provider<MyApplicationManager>
                
                val fragment: FragmentController

                @Component.Builder
                interface Factory {
                    fun create(): MyFragmentComponent 
                }
            }

            fun test() {
                val factory: MyApplicationComponent.Factory = Yatagan.builder(MyApplicationComponent.Factory::class.java)
                val appComponent = factory.create("foo")
                 
                with(appComponent.activityFactory.create("bar")) {
                    val appManager = appManager
                    val appManagerLazy = appManagerLazy
                    val appManagerProvider = appManagerProvider
                    assert(appManager != appManagerLazy.get())
                    assert(appManager != appManagerProvider.get())
                    assert(appManagerLazy.get() == appManagerLazy.get())
                    assert(appManagerProvider.get() != appManagerProvider.get())
                }

                with(appComponent.activityFactory.create("bar").fragmentFactory.create()) {
                    val appManager = appManager
                    val appManagerLazy = appManagerLazy
                    val appManagerProvider = appManagerProvider
                    assert(appManager != appManagerLazy.get())
                    assert(appManager != appManagerProvider.get())
                    assert(appManagerLazy.get() == appManagerLazy.get())
                    assert(appManagerProvider.get() != appManagerProvider.get())
                }
            }
        """,
        )

        compileRunAndValidate()
    }

    @Test
    fun `alias binding across component boundary`() {
        givenKotlinSource("test.TestCase", """
            import com.yandex.yatagan.Module            
            import com.yandex.yatagan.Component            
            import com.yandex.yatagan.Binds
            import com.yandex.yatagan.BindsInstance
            import javax.inject.Inject      

            interface MyApi
            class MyImpl : MyApi
            @Module
            interface TestSubModule {
                @Binds fun api(i: MyImpl): MyApi
            }
            @Module(subcomponents = [TestSubComponent::class])
            interface TestModule
            class Consumer @Inject constructor(api: MyApi)
            @Component(modules = [TestModule::class])
            interface TestComponent {
                val sub: TestSubComponent.Creator

                @Component.Builder
                interface Creator {
                    fun create(@BindsInstance impl: MyImpl): TestComponent
                }
            }
            @Component(isRoot = false, modules = [TestSubModule::class])
            interface TestSubComponent {
                val consumer: Consumer
                @Component.Builder
                interface Creator {
                    fun create(): TestSubComponent
                }
            }
        """.trimIndent())

        compileRunAndValidate()
    }

    @Test
    fun `subcomponents - one more advanced case`() {
        givenKotlinSource(
            "test.TestCase",
            """
            import javax.inject.*
            import com.yandex.yatagan.*

            @Scope annotation class ActivityScoped
             
            interface Theme
            
            class DefaultTheme @Inject constructor() : Theme
            
            class DarkTheme @Inject constructor(): Theme
            
            class RootClass

            @ActivityScoped
            class ActivityScopedClass @Inject constructor()

            class CameraSettings @Inject constructor(val theme: Theme)
            
            open class Activity
            
            class SettingsActivity : Activity()
            
            @Singleton class SingletonClass @Inject constructor()
             
            //////////////////////////////////////
            
            @Module(subcomponents = [MainActivityComponent::class, SettingsActivityComponent::class])
            interface ApplicationModule {
                companion object {
                    @Provides
                    fun rootClass(): RootClass = RootClass()
                }
            }
            
            @Module(subcomponents = [ProfileSettingsFragmentComponent::class, ProfileFragmentComponent::class])
            class SettingsActivityFragmentModule (private val settingsActivity: SettingsActivity) {
                @Provides fun activity(): Activity = settingsActivity
            }
            
            @Module
            object MainActivityModule {
                @Provides
                fun activity() = Activity()
            }
            
            @Module(subcomponents = [CameraFragmentComponent::class])
            interface CameraFragmentModule {
            }
            
            @Module(subcomponents = [CameraFragmentComponent::class, ProfileFragmentComponent::class])
            interface ProfileCameraModule
            
            @Module
            interface DefaultThemeModule {
                @Binds fun theme(i: DefaultTheme): Theme
            }
            @Module
            interface DarkThemeModule {
                @Binds fun theme(i: DarkTheme): Theme
            }
            
            //////////////////////////////////////
            
            @Singleton
            @Component(modules = [ApplicationModule::class])
            interface ApplicationComponent {    
                val mainActivity: MainActivityComponent.Factory
                val settingsActivity: SettingsActivityComponent.Factory
            }
            
            @ActivityScoped
            @Component(modules = [CameraFragmentModule::class, ProfileCameraModule::class, DefaultThemeModule::class, MainActivityModule::class],
                isRoot = false)
            interface MainActivityComponent {
                val cameraFragmentComponent: CameraFragmentComponent.Factory
                val profileFragmentComponent: ProfileFragmentComponent.Factory
                
                fun rootClass(): RootClass
            
                @Component.Builder
                interface Factory {
                    fun create(): MainActivityComponent
                }
            }
            
            @ActivityScoped
            @Component(modules = [DarkThemeModule::class, SettingsActivityFragmentModule::class], isRoot = false)
            interface SettingsActivityComponent {
                fun rootClass(): RootClass
                
                val profileFragmentComponent: ProfileFragmentComponent.Factory
                val profileSettingsFragmentComponent: ProfileSettingsFragmentComponent.Factory
                
                @Component.Builder
                interface Factory {
                    fun create(settingsActivityFragmentModule: SettingsActivityFragmentModule): SettingsActivityComponent
                }
            }
            
            @Component(isRoot = false)
            interface CameraFragmentComponent {
                fun cameraSettings(): CameraSettings
            
                @Component.Builder
                interface Factory {
                    fun create(): CameraFragmentComponent
                }
            }
            
            @Component(isRoot = false)
            interface ProfileFragmentComponent {
                fun activityScoped(): ActivityScopedClass
                fun activity(): Activity
                fun singleton(): SingletonClass
            
                @Component.Builder
                interface Factory {
                    fun create(): ProfileFragmentComponent
                }
            }
            
            @Component(isRoot = false)
            interface ProfileSettingsFragmentComponent {
                fun cameraSettings(): CameraSettings
            
                @Component.Builder
                interface Factory {
                    fun create(): ProfileSettingsFragmentComponent
                }
            }
            
            fun test() {
                val c = Yatagan.create(ApplicationComponent::class.java)
                val settingsActivityFragmentModule = SettingsActivityFragmentModule(SettingsActivity())
                
                val mainActivityC = c.mainActivity.create()
                val settingsActivityC = c.settingsActivity.create(settingsActivityFragmentModule)
                
                val cameraFragmentC = mainActivityC.cameraFragmentComponent.create()
                val profileFragmentC = mainActivityC.profileFragmentComponent.create()
            
                val profileFragmentFromSettingsC = settingsActivityC.profileFragmentComponent.create()
                val profileSettingsFragmentC = settingsActivityC.profileSettingsFragmentComponent.create()
            
                assert(mainActivityC.rootClass() !== settingsActivityC.rootClass())

                assert(profileFragmentC.activity()::class == Activity::class)
                assert(profileFragmentFromSettingsC.activity()::class == SettingsActivity::class)

                assert(profileFragmentC.activityScoped() === profileFragmentC.activityScoped())
                assert(profileFragmentFromSettingsC.activityScoped() === profileFragmentFromSettingsC.activityScoped())
                assert(profileFragmentFromSettingsC.activityScoped() !== profileFragmentC.activityScoped())

                assert(profileFragmentC.singleton() === profileFragmentFromSettingsC.singleton())
                assert(cameraFragmentC.cameraSettings().theme::class == DefaultTheme::class)
                assert(profileSettingsFragmentC.cameraSettings().theme::class == DarkTheme::class)
            }
        """,
        )

        compileRunAndValidate()
    }

    @Test
    fun `implicit subcomponent inclusion`() {
        givenKotlinSource("test.TestCase", """
            import com.yandex.yatagan.*
            import javax.inject.*

            @Scope annotation class Sub
            @Scope annotation class Sub2

            @Singleton class Foo @Inject constructor()
            interface MyDep
            @Module class MyModule(@get:Provides val i: Int)
            @Module class MyImplicitModule { @get:Provides val i: Long get() = 1L }

            @Sub2 @Component(isRoot = false)
            interface Sub2Component {
                val foo: Foo
                val opt: Optional<FeatureComponent.Factory>
            }

            @Sub @Component(isRoot = false)
            interface SubComponent1 {
                val opt: Optional<FeatureComponent.Factory>

                @Component.Builder
                interface Builder { fun create(): SubComponent1 }
            }
            
            @Sub @Component(isRoot = false)
            interface SubComponent2 {
                val sub2: Sub2Component
                val foo: Foo
            }

            @Sub @Component(isRoot = false, modules = [MyModule::class], dependencies = [MyDep::class])
            interface SubComponent3 {
                val i: Int
                val d: MyDep
                val d2: Double
                val foo: Foo
                val sub4: SubComponent4
            }
            @Component(isRoot = false, modules = [MyImplicitModule::class])
            interface SubComponent4 {
                val i: Long
            }

            @Singleton @Component
            interface RootComponent {
                val sub1: SubComponent1.Builder
                val sub2: SubComponent2
                fun createSubComponent3(dep: MyDep, mod: MyModule, @BindsInstance d: Double): SubComponent3
                
                @Component.Builder
                interface Factory { fun create(@BindsInstance features: Features): RootComponent }
            }
            
            class Features(
                val isEnabled: Boolean,
            ) {
                @Condition(Features::class, "isEnabled")
                annotation class IsEnabled
            }
            
            @Component(isRoot = false)
            @Conditional(Features.IsEnabled::class)
            interface FeatureComponent {
                val foo: Provider<Foo>
                
                fun createFeatureComponent2(dep: MyDep): FeatureComponent2
                
                @Component.Builder
                interface Factory {
                    fun create(): FeatureComponent
                }
            }

            @Component(isRoot = false, dependencies = [MyDep::class])
            @Conditional(Features.IsEnabled::class)
            interface FeatureComponent2 {
                val dep: MyDep
            }

            fun test() {
                val root: RootComponent = Yatagan.builder(RootComponent.Factory::class.java).create(Features(true))
                val foo: Foo = root.sub1.create().opt.get().create().foo.get()
                root.sub2.foo

                val dep = object : MyDep {}
                val mod = MyModule(740)
                val sub3 = root.createSubComponent3(dep, mod, 0.5)
                assert(sub3.foo === foo)
                assert(sub3.d === dep)
                assert(sub3.d2 == 0.5)
                assert(sub3.i == 740)

                root.sub1.create().opt.get().create().createFeatureComponent2(dep)

                val root2: RootComponent = Yatagan.builder(RootComponent.Factory::class.java).create(Features(false))
                assert(!root2.sub1.create().opt.isPresent)
                assert(!root2.sub2.sub2.opt.isPresent)
            }
        """.trimIndent())

        compileRunAndValidate()
    }

    @Test
    fun `create, builder & auto-builder usage`() {
        givenKotlinSource("test.TestCase", """
            import com.yandex.yatagan.*
            import javax.inject.*

            interface ComponentBase {
                interface Builder {
                    fun create(): ComponentBase
                }
            }

            @Module class MyModule {
                @get:Provides val i: Int = 2
            }

            @Component(modules = [MyModule::class])
            interface ComponentFoo {
                val i: Int
            }

            interface MyDependency1
            interface MyDependency2
            @Module class MyModule2(@get:Provides val i: Int)

            @Component(modules = [
                MyModule2::class,
            ], dependencies = [
                MyDependency1::class,
                MyDependency2::class,
            ])
            interface ComponentBar {
                val i: Int
            }

            @Component interface ComponentBaz : ComponentBase {
                @Component.Builder interface Builder : ComponentBase.Builder
            }
            @Component.Builder interface Builder { fun create(): ComponentBaz }
            @Component(isRoot = false) interface ComponentQu {
                @Component.Builder interface Builder { fun create(): ComponentQu }
            }
            abstract class Quu {
                @Component.Builder interface Builder
            }

            inline fun <reified E : Throwable> throws(message: String, block: () -> Unit) {
                try {
                    block()
                    throw AssertionError("Not reached")
                } catch (e: Throwable) {
                    if (e is E) {
                        if(e.message.toString() != message) throw AssertionError("Wrong error", e)
                    } else throw e
                }
            }

            fun test() {
                Yatagan.create(ComponentFoo::class.java)
                Yatagan.autoBuilder(ComponentFoo::class.java).create()
                Yatagan.autoBuilder(ComponentFoo::class.java)
                    .provideInput(MyModule())
                    .provideInput(MyModule())  // overwriting is allowed
                    .create()

                throws<IllegalArgumentException>("Argument of class java.lang.Object is not expected. Should be one of: test.MyModule") {
                    Yatagan.autoBuilder(ComponentFoo::class.java).provideInput(Any())
                }

                val builder = Yatagan.autoBuilder(ComponentBar::class.java)
                throws<IllegalStateException>("Can not create component instance as (at least) the following required input is missing: test.MyDependency2") {
                    builder
                        .provideInput(MyModule2(228))
                        .provideInput(MyModule2(740))
                        .provideInput(object : MyDependency1 {}, MyDependency1::class.java)
                        .create()
                }

                val bar = builder.provideInput(object : MyDependency2 {}, MyDependency2::class.java).create()
                assert(bar.i == 740)

                throws<IllegalStateException>("Can not create component instance as (at least) the following required input is missing: test.MyDependency1") {
                    Yatagan.create(ComponentBar::class.java)
                }
            
                throws<IllegalArgumentException>("Auto-builder can't be used for interface test.ComponentBaz, " +
                        "because it declares an explicit builder. Please use `Yatagan.builder()` instead") {
                    Yatagan.autoBuilder(ComponentBaz::class.java)
                }
            
                throws<IllegalArgumentException>("interface test.ComponentFoo is not a builder for a Yatagan component") {
                    Yatagan.builder(ComponentFoo::class.java)
                }
            
                throws<IllegalArgumentException>("No enclosing component class found for interface test.Builder") {
                    Yatagan.builder(Builder::class.java)
                }
            
                throws<IllegalArgumentException>("interface test.ComponentQu is not a root Yatagan component") {
                    Yatagan.builder(ComponentQu.Builder::class.java)
                }
    
                throws<IllegalArgumentException>("class test.Quu is not a root Yatagan component") {
                    Yatagan.builder(Quu.Builder::class.java)
                }
            }
        """.trimIndent())

        compileRunAndValidate()
    }
}
