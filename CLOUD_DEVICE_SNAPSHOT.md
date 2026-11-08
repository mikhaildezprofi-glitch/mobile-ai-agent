# Device worktree snapshot

Created: 2026-11-09
Source local HEAD: 83340299532a83ea1225ec67cb00dba94d020a25

This branch snapshots the phone worktree source and automation files. It intentionally excludes generated build output, model weights, the separate llama.cpp checkout, local.properties, runtime status files, private backup binaries, and individual .bak rollback copies. Those remain on the device pending private cloud backup.
