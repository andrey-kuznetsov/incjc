# Simple incremental Java compiler

## Build
`./gradlew jar`

## Launch
- in terminal: `java -jar incjc-1.0-SNAPSHOT.jar <classpath> <sourcepath>`;
- in IntelliJ Idea: create run configuration for `incjc.IncJC` main class.

`INCJC_DEBUG` environment variable set to `1` enables debug output.

## Assumptions / limitations
- classpath is a single directory, not a list;
- classpath should not be modified externally;
- no `javac` arguments support.

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
