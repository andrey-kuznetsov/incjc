# Simple incremental Java compiler

## Build
`./gradlew jar`

## Launch
- in terminal: `java -jar incjc-1.0-SNAPSHOT.jar <classpath> <sourcepath>`;
- in IntelliJ Idea: create run configuration for `incjc.IncJC` main class.

`<classpath>` will be wiped out at first run. 

`<sourcepath>` should contain package directories and / or `.java` files; for example, in typical Gradle / Maven layout, `src/main/java` -- is OK, while `src/main` is not suitable.

Extra classpath entries (external dependencies) can be provided by setting `CLASSPATH` environment variable.
`INCJC_DEBUG` environment variable set to `1` enables debug output.

## Assumptions / limitations
- classpath is a single directory, not a list;
- classpath should not be modified externally;
- no `javac` arguments support.

## Implementation description
Implementation maintains persistent meta-information stored in `$HOME/.incjc-meta-<hash>` directory. It consists of 3 parts:
- class name to declaring `.java` file name mapping;
- dependency graph edges, persisted as plain list of `SomeClass->DependingClass` lines;
- source file name to source file contents hash mapping.

Initial run compiles all sources with `javac` and creates meta-information from scratch.

Subsequent runs detect changed sources by computing hashes and comparing them to previously saved hashes, then builds wider set of sources to be (re)compiled, using dependency graph, and finally updates meta-information.

In case of incremental compilation `javac` calls are made using temporary classpath / destination directories so that compilation errors will not lead to previous state corruption.

## Known issues
Some scenarios with private classes defined together with public class in the same source file are not supported. Example follows.

1. Start with `A.java`:
```java
public class A {}
class X {}
```
2. run incjc;
3. Do not change `A.java`, add `B.java`:
```java
public class B {}
class X {}
```
4. run incjc; expected behavior: compilation failure (duplicate class), actual behavior: incjc succeeds.
