# Upstream

`compiler/multiplatform-parsing/common/src` of the Kotlin repository (https://github.com/JetBrains/kotlin), branch
`master`, commit `dc82a39b65ca54a53afb0e47b368a45206d9035c`, unchanged. Apache License 2.0: `LICENSE.txt` and
`NOTICE.txt` are the repository's.

It is the Kotlin compiler's multiplatform lexer and parser. The generated lexers in `gen/` come from `Kotlin.flex` and
`KDoc.flex`; they are kept as they are in the repository, and the grammar files only for reference.

To update, from a checkout of the Kotlin repository:

```bash
rm -rf kotlin-parser/upstream/gen kotlin-parser/upstream/org
git -C <kotlin> archive <commit> compiler/multiplatform-parsing/common/src | tar -x -C kotlin-parser/upstream --strip-components=4
```

and change the commit above, and `syntax-api` in `gradle/libs.versions.toml` to the version the Kotlin repository
uses at that commit (`org-jetbrains-syntax-api` in its `gradle/libs.versions.toml`).
