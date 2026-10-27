package io.github.zyrouge.symphony.utils

import okhttp3.OkHttpClient

val HttpClient = OkHttpClient.Builder().addInterceptor(io.github.zyrouge.symphony.services.diagnostics.DiagnosticInterceptor("http")).cache(null).build()
