# GosCompatTests

`GosCompatCheckApp` is a standalone helper app with a manual UI.

`GosCompatMapsScanTests` covers compatibility with apps using native libraries that scan 
`/proc/self/maps` and read selected mapped memory ranges.

You can run tests from the checkout root via this directory's `TEST_MAPPING`:

```sh
atest --test-mapping frameworks/base/tests/GosCompatTests:gos_postsubmit
```

Alternatively, view the `TEST_MAPPING` file or Android.bp files and run test modules directly with 
`atest`.
