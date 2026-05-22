# GosCompatTests

`GosCompatCheckApp` is a standalone helper app with a manual UI. See subdirectories for descriptions
of each test.

You can run tests from the checkout root via this directory's `TEST_MAPPING`:

```sh
atest --test-mapping frameworks/base/tests/GosCompatTests:gos_postsubmit
```

Alternatively, view the `TEST_MAPPING` file or Android.bp files and run test modules directly with
`atest`.
