package com.qushuiyin.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        /*
         * 记录当前处于前台的 Activity。
         *
         * 用途只有一个：解析时把离屏 WebView 挂到一个真实窗口上。没挂窗口的 WebView
         * 不参与渲染，`requestAnimationFrame` 不回调、`document.visibilityState` 可能是
         * hidden，页面很可能因此跳过数据预取——「桌面浏览器能拿到、手机拿不到」
         * 的差别经常就在这里。
         *
         * 存弱引用，Activity 销毁时清掉，不给泄漏留口子。
         */
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                current = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (current?.get() === activity) current = null
            }
        })
    }

    companion object {
        lateinit var instance: App
            private set

        @Volatile
        private var current: WeakReference<Activity>? = null

        /** 当前前台 Activity，已销毁或为空时返回 null */
        val currentActivity: Activity?
            get() = current?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }
}
