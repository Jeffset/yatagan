# Dagger Lite
Like Dagger2 only lighter (supports only specific subset of original API). Supports dynamic graphs with feature-scopes
(merged functionality of the former Dagger Whetstone).

# Roadmap

## MVP
Solution _only works with valid Dagger code_ for fast and flexible prototyping.
It is undefined behavior when the code is invalid: crashes, incorrect code generation, etc. may occur.
Such behaviour should later be fixed with validation steps.

### Core Dagger API

- [x] `@Component` - only interface allowed
    - [x] `@Component.Factory` - only interface allowed
- [x] `@Subcomponent` - implemented as `@Component(isRoot=false)`
- [x] `@Component(dependencies=...)`
- [x] `@Module`
    - [x] `@Binds` - no scope allowed
    - [x] `@Provides`
    - [x] kotlin objects support
    - [x] companion object support
    - [x] module with instance support

Explicitly not supported API
- `@Component.Builder`/`@Subcomponent.Builder`
- `@BindsOptionalOf`
- `@IntoMap`, `@MapKey`, ..

### Whetstone API

- [ ] `@BindIn` is replaced by:
  - [x] `@Conditional(/*features*/, /*variant*/` - variant - new conception instead of target modules
  - [x] Features for subcomponents
  - [ ] `@Module(subscribers=...)` or anything alike for event subscription
- [x] `@ConditionHolder` - dropped and replaced by automatic implementation.
- [x] `@Condition`, `@AnyCondition`, ...
- [x] `@BindsFeatureScoped` -> `@Binds`
- [ ] `@ProvidesFeatureScoped` -> `@Provides(...)`
- [ ] Manual `Optional<T>` binding support (legacy).
      _We don't need this if we're ready to migrate all manual bindings to `@Conditional`
      and `@Provides(/*feature*/)` at once._ 
- [ ] Short-circuit condition evaluation preservation.

## Full solution

- [ ] Validation and full-blown error messages.
- [ ] Whetstone Validation
- [ ] Multi-bindings
  - [ ] `@IntoSet`
  - [ ] `@ElementsIntoSet`
  - [ ] `@Multibinds` - _low priority - not used in our project._
- [ ] `@Reusable` - _low priority - not used in our project._