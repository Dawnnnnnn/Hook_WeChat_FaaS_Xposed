package com.dawnnnnnn.wechat_cloud_function_hook

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.ktor.application.*
import io.ktor.features.ContentNegotiation
import io.ktor.features.StatusPages
import io.ktor.gson.gson
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

data class CallWXRequest(val appid: String, val jsapi_name: String, val data: String)

class WeChatHook : IXposedHookLoadPackage {

    private var serverStarted = false
    private var callWXAsyncRequestCounter = 0
    private var callAppId: String? = null
    private val hookedAppIds = mutableSetOf<String>()
    private val label = "[WX-FaaS-HOOK]"
    private val requestLabel = "[WX-FaaS-HOOK-Network]"
    private val logList = mutableListOf<String>()
    private val maxLogSize = 1500
    private var appBrandCommonBindingJniInstance: Any? = null

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.tencent.mm") {
            log("$label Find app: ${lpparam.packageName}")
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    @Throws(Throwable::class)
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.thisObject as Context
                        if (getProcessName(context) == "com.tencent.mm:appbrand0") {
                            XposedHelpers.findAndHookMethod(
                                "com.tencent.mm.plugin.appbrand.v",
                                lpparam.classLoader,
                                "getAppId",
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(param: MethodHookParam) {
                                        val appId = param.result as? String
                                        appId?.let {
                                            startServerIfNeeded()
                                            hookInvokeHandlersIfNeeded(lpparam, appId)
                                        }
                                    }
                                })
                        }
                    }
                })
        }
    }

    private fun startServerIfNeeded() {
        if (!serverStarted) {
            startServer()
            serverStarted = true
        }
    }

    private fun hookInvokeHandlersIfNeeded(
        lpparam: XC_LoadPackage.LoadPackageParam,
        appId: String
    ) {
        if (!hookedAppIds.contains(appId)) {
            hookInvokeHandlers(lpparam, appId)
            hookedAppIds.add(appId)
        }
    }

    private fun log(message: String) {
        XposedBridge.log(message)
        synchronized(logList) {
            if (logList.size >= maxLogSize) {
                logList.removeAt(0)
            }
            logList.add(message)
        }
    }

    private fun getProcessName(context: Context): String? {
        val pid = Process.myPid()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private fun hookInvokeHandlers(lpparam: XC_LoadPackage.LoadPackageParam, appId: String) {
        XposedHelpers.findAndHookMethod(
            "com.tencent.mm.appbrand.commonjni.AppBrandCommonBindingJni",
            lpparam.classLoader,
            "nativeInvokeHandler",
            String::class.java,
            String::class.java,
            String::class.java,
            Int::class.java,
            Boolean::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (appBrandCommonBindingJniInstance == null) {
                        appBrandCommonBindingJniInstance = param.thisObject
                    }
                    callWXAsyncRequestCounter = param.args[3] as Int
                    log("$requestLabel [$appId] [$callWXAsyncRequestCounter] == [requests]: jsapi_name=${param.args[0]}, data=${param.args[1]}, str3=${param.args[2]}, z15=${param.args[4]}")
                }
            })

        XposedHelpers.findAndHookMethod(
            "com.tencent.mm.appbrand.commonjni.AppBrandJsBridgeBinding",
            lpparam.classLoader,
            "invokeCallbackHandler",
            Int::class.java,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    log("$requestLabel [$appId] [${param.args[0]}] == [response]: ${param.args[1]}")
                }
            })
    }

    private fun startServer() {
        log("$label start embeddedServer at 0.0.0.0:59999")
        embeddedServer(Netty, port = 59999) {
            install(ContentNegotiation) {
                gson {
                    setPrettyPrinting()
                }
            }
            install(StatusPages) {
                exception<Throwable> { cause ->
                    call.respond(HttpStatusCode.InternalServerError, cause.localizedMessage)
                }
            }
            routing {
                get("/") {
                    call.respond("ok")
                }
                post("/CallWX") {
                    val request = call.receive<CallWXRequest>()
                    if (request.appid.isNotEmpty() && request.jsapi_name.isNotEmpty() && request.data.isNotEmpty()) {
                        val response = callWX(request.appid, request.jsapi_name, request.data)
                        call.respond(mapOf("result" to response))
                    } else {
                        call.respond(HttpStatusCode.BadRequest, "Missing parameters")
                    }
                }
                get("/wx_log") {
                    call.respond(logList)
                }
            }
        }.start(wait = false)
    }

    private fun callWX(appid: String, jsapiName: String, data: String): String {
        callAppId = appid
        callWXAsyncRequestCounter++
        log("$label receive callWX api data，to do invokeMethod")
        log("$label check appBrandCommonBindingJniInstance cache: $appBrandCommonBindingJniInstance")
        appBrandCommonBindingJniInstance?.let {
            try {
                val invokeMethod = it::class.java.getMethod(
                    "nativeInvokeHandler",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    Int::class.java,
                    Boolean::class.java
                )
                invokeMethod.isAccessible = true
                invokeMethod.invoke(it, jsapiName, data, "{}", callWXAsyncRequestCounter, true)
                log("$label invokeMethod success: $callAppId-$callWXAsyncRequestCounter")
            } catch (e: Exception) {
                log("$label Exception in callWX with cached instance: ${e.message}")
            }
        } ?: log("$label AppBrandCommonBindingJniInstance is null")

        return "$callAppId-$callWXAsyncRequestCounter"
    }
}