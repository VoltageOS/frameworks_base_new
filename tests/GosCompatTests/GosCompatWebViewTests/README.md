`GosCompatWebViewTests` covers WebView default user agent behavior. It relaunches
the helper app process for each attempt so `WebSettings.getDefaultUserAgent()`
is exercised from a fresh app start.

One test covers WebView default user agent startup when an app worker thread
holds an app lock and the app UI thread is blocked trying to acquire the same
lock on the Android main looper. Another test verifies the default WebView user
agent is reduced without depending on the exact Vanadium major version.

This is especially evident with apps not following recommended practices for Google Mobile Ads SDK.
For example, an app can call this on the main thread:

```java
new AdLoader.Builder(context, adUnitId)
        .forNativeAd(nativeAdLoadedListener)
        .withNativeAdOptions(nativeAdOptions)
        .withAdListener(adListener)
        .build() // This causes the deadlock since this is on main thread
        .loadAd(adRequest);
```
Google recommends to only call AdLoader.Builder from a worker thread [^1]. However, not all apps
follow these recommendations. The nuance is that `loadAd` can only be called on the main thread, but
the creation of the AdLoader should be done on a worker thread.

During app startup, a worker thread in the Mobile Ads SDK holds a singleton lock and calls
`WebSettings.getDefaultUserAgent()`. When the `WebViewFasterGetDefaultUserAgent` feature for WebView
is disabled, this blocks the worker thread until its task posted to the main thread to initialize
WebView finishes.

Meanwhile, the main thread of the app enters Mobile Ads SDK by the function calls above. Inside of
`AdLoader.Builder#build`, if the Dynamite ads module is not available, the library has a local
fallback path which attempts to acquires the singleton lock that the worker thread is holding. The
result is that the main thread gets blocked on a lock held by the worker thread which is waiting for
main thread to process its task.

Dynamite ads module can be unavailable if Play services + Play Store are not installed (or have some
other issue). This would block users from using any app with SDK used in this way.

By enabling `WebViewFasterGetDefaultUserAgent`, the worker thread no longer waits on the
initialization task posted to main thread to finish and gets a user agent immediately. This works
around this deadlock.

[^1]: https://developers.google.com/admob/android/native#build
