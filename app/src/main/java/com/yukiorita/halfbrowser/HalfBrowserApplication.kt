package com.yukiorita.halfbrowser

import android.app.Application

class HalfBrowserApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
