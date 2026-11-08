# Recovery archives

These archives preserve the phone's rollback material before local cleanup:

- `native-backups-20261109/native-libraries.tar.gz`: native libraries from the pre-MTMD backup and the prebuilt JNI rollback directory (including pre-SONAME and pre-OpenMP-fix copies).
- `source-rollback-files-20261109.tar.gz`: source `.bak` and `.backup` files from the parent Android project and the nested llama.cpp Android module.

They are recovery-only. The build workflow does not extract or link these archives.
