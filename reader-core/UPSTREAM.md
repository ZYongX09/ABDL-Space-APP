# FlowReader Upstream

- Repository: https://github.com/HuZaiGong/flowreader.git
- Imported from commit: c56512d1b55aaf332fbd7ab10c0d94f260a75992
- License: GPL-3.0; the upstream license is preserved in `LICENSE.flowreader`.

## Scope

The planned import keeps FlowReader's domain, data, parser, and reader capabilities inside the `reader-core` Android library. Its standalone application shell, navigation, Hilt wiring, backup features, and unrelated library screens are excluded.

Upstream code will be migrated manually in later tasks, file by file, with package changes and integration-specific adaptations kept reviewable. This task only establishes the module and provenance record; it does not import entities, DAOs, parsers, or reader UI.
