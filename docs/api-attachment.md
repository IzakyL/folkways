# API compile attachment

Core publishes an `api` classifier attachment for compiling external add-ons. Its public types under
`core/api`, `front/api` and `base/task` include the recursive Folkways type dependencies of their public
and protected signatures (including generics, annotations, nested types and inheritance). Private types
and method-body dependencies do not expand the attachment. An exposed engine or UI implementation type
fails the build instead of silently making that implementation public. The attachment is for compilation
only: run add-ons with the complete Core jar, the required Folkways modules and their normal dependencies.
Minecraft, NeoForge, LDLib2 and other external APIs remain separate compile dependencies.

`./gradlew :core:checkApiConsumer` verifies the packaged signature closure and compiles the sample add-on
in `tests/api-consumer`. Its classpath contains only the API attachment and Core's declared external
compile dependencies; implementation jars, project class directories, source fallback and annotation
processors cannot supply missing Folkways types. This check runs with Gradle `check` and therefore CI.
`./gradlew :core:apiClosureTest` tests the closure tool with synthetic bytecode and needs no game classes.
