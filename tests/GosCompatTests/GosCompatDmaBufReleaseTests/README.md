# GosCompatDmaBufReleaseTests

This module is a targeted regression test for a Pixel kernel memory error (KASAN incompatability
from rebuilding pointers without the original tags) in the Samsung DMA-BUF secure chunk heap release 
path.

The tests will panic on a KASAN kernel without the patch for the memory error. Note that these tests 
are designed to be non-priv apps.

## Access rules

The helper app intentionally opens the heap devices directly. This is allowed by the device policy
and file modes:

- `vendor/etc/ueventd.rc` sets both heap devices to mode `0444`.
- Vendor SEPolicy labels both devices as `dmabuf_system_secure_heap_device`.
- `system/sepolicy/private/app.te` allows non-isolated app domains to read and open
  `dmabuf_system_secure_heap_device` character devices.

The native helper only accepts the allowlisted heap names above. It does not use the intent extra as
an arbitrary file path.

## Relationship to existing AOSP tests

The native helper follows the same basic allocation and release pattern as the existing DMA-BUF heap
unit tests:

- `system/memory/libdmabufheap/tests/dmabuf_heap_test.cpp`: `DmaBufHeapTest.Allocate` allocates
  DMA-BUF fds and closes them to release the buffers.
- `system/memory/libdmabufheap/tests/dmabuf_heap_test.cpp`: `DmaBufHeapTest.RepeatedAllocate`
  repeats the same allocate and close path.
- `system/memory/libdmabufheap/tests/dmabuf_heap_test.c`: `libdmabufheaptest()` exercises the C
  wrapper around the same libdmabufheap allocation flow.

Note that these tests did not panic. In general, CTS tests in AOSP don't exercise these
Pixel-specific paths or they don't test process teardown.

This helper intentionally does not link `libdmabufheap`, because it is built inside the SDK-built
helper APK and needs to target Pixel secure heap device names directly. Instead, it opens
`/dev/dma_heap/vframe-secure` or `/dev/dma_heap/vstream-secure` and issues `DMA_HEAP_IOCTL_ALLOC`,
then either closes the returned fd during manual release or leaves it open until process teardown
closes it.

The protected EGL tests use the public EGL/GLES shape that is closest to the GPU driver usage:
`EGL_EXT_protected_content` on a protected context and pbuffer surface, plus
`GL_EXT_protected_textures` for protected texture storage. The tests cover explicit release, normal
app stop, and force stop because different devices can release the underlying DMA-BUF from different
threads. For example, shiba reproduced the panic from the Mali memory purge thread during explicit
protected EGL resource release.

`DmaBufReleaseSequenceTests` repeats the same manual release sequence as the standalone UI's Run all
button in one helper process. This catches timing-sensitive cleanup paths better than the isolated
per-workload tests because shiba needed several Run all attempts before reproducing the panic.

## Useful atest commands

Run the whole module:

```sh
atest GosCompatDmaBufReleaseTests
```

Run the protected EGL cases individually:

```sh
ATEST_MODULE=GosCompatDmaBufReleaseTests
TEST_PKG=app.grapheneos.goscompat.dmabufrelease.tests

atest "${ATEST_MODULE}:${TEST_PKG}.ProtectedEglReleaseTests#\
protectedEglResourcesCanBeManuallyReleasedAfterReady"
atest "${ATEST_MODULE}:${TEST_PKG}.ProtectedEglReleaseTests#\
protectedEglResourcesCanBeStoppedAfterReady"
atest "${ATEST_MODULE}:${TEST_PKG}.ProtectedEglReleaseTests#\
protectedEglResourcesCanBeForceStoppedAfterReady"
```

Run the direct secure chunk heap parameterized cases:

```sh
ATEST_MODULE=GosCompatDmaBufReleaseTests
TEST_PKG=app.grapheneos.goscompat.dmabufrelease.tests

atest "${ATEST_MODULE}:${TEST_PKG}.SecureChunkHeapReleaseTests#\
secureChunkHeapBufferCanBeReleasedAfterReady"
```

Run the repeated UI-shaped manual release sequence:

```sh
ATEST_MODULE=GosCompatDmaBufReleaseTests
TEST_PKG=app.grapheneos.goscompat.dmabufrelease.tests

atest "${ATEST_MODULE}:${TEST_PKG}.DmaBufReleaseSequenceTests#\
manualReleaseRunAllSequenceCanBeRepeatedInOneProcess"
```
