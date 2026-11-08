#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "quickjs/quickjs.h"

// QuickJS Android 绑定（DSH 原生版）。
//
// 设计要点（契约：workflow 的 `agent()` 必须是异步 Promise，且 parallel/pipeline 要真并发）：
// - `Run()` 在调用线程上新建 runtime/context，执行一段 JS，然后循环
//   「跑 job queue → 把宿主 completion 灌回并 resolve Promise → 再看根 Promise 是否 settle」；
// - 宿主 hook 分两类：`__dshCallSync`（phase/log，直接同步调 Kotlin）与
//   `__dshCallAsync`（agent，创建 Promise 后把 callId 交给 Kotlin，Kotlin 完成后调
//   `nativeComplete` 把 completion 塞进队列并唤醒 JS 线程）；
// - JS 线程在等待 completion 时阻塞在条件变量上，不忙转；
// - `interrupt()` 置原子标记，QuickJS 的 interrupt handler 让脚本抛异常；
//   等待中的循环也检查该标记，立刻按 interrupted 返回。
//
// 字符串全部走 UTF-8 byte[]（不用 NewStringUTF/GetStringUTFChars）：JS 脚本/参数里
// 必然出现中文与 emoji，modified UTF-8 的边界不值得赌。

namespace {

/// 一次异步 host 调用的 JS 侧结算器（resolve/reject）。
struct PendingCall {
    JSValue resolve = JS_UNDEFINED;
    JSValue reject = JS_UNDEFINED;
};

/// 宿主线程回灌的一次结算。
struct Completion {
    int call_id = 0;
    bool ok = false;
    std::string payload;
};

/// 在 JSON 字符串里转义（UTF-8 原样保留；只转 JSON 必须转的字符）。
void AppendJsonString(std::string* out, const std::string& text) {
    out->push_back('"');
    for (unsigned char ch : text) {
        switch (ch) {
            case '"': out->append("\\\""); break;
            case '\\': out->append("\\\\"); break;
            case '\b': out->append("\\b"); break;
            case '\f': out->append("\\f"); break;
            case '\n': out->append("\\n"); break;
            case '\r': out->append("\\r"); break;
            case '\t': out->append("\\t"); break;
            default:
                if (ch < 0x20) {
                    char buf[7];
                    std::snprintf(buf, sizeof(buf), "\\u%04x", ch);
                    out->append(buf);
                } else {
                    out->push_back(static_cast<char>(ch));
                }
                break;
        }
    }
    out->push_back('"');
}

std::string JsValueToUtf8(JSContext* ctx, JSValueConst value) {
    size_t length = 0;
    const char* chars = JS_ToCStringLen(ctx, &length, value);
    if (chars == nullptr) return std::string();
    std::string result(chars, length);
    JS_FreeCString(ctx, chars);
    return result;
}

/// 读对象上的字符串属性；读不到返回空。
std::string StringProperty(JSContext* ctx, JSValueConst object, const char* name) {
    JSValue value = JS_GetPropertyStr(ctx, object, name);
    if (JS_IsException(value)) {
        JS_FreeValue(ctx, JS_GetException(ctx));
        return std::string();
    }
    std::string result;
    if (JS_IsString(value)) result = JsValueToUtf8(ctx, value);
    JS_FreeValue(ctx, value);
    return result;
}

/// 与上游 `renderThrown` 同序：先 stack，再 message，最后 String()。
std::string RenderThrown(JSContext* ctx, JSValueConst value) {
    if (JS_IsObject(value)) {
        std::string stack = StringProperty(ctx, value, "stack");
        if (!stack.empty()) return stack;
        std::string message = StringProperty(ctx, value, "message");
        if (!message.empty()) return message;
    }
    return JsValueToUtf8(ctx, value);
}

class JsMachine {
public:
    JsMachine(JNIEnv* env, jobject bridge) {
        if (env->GetJavaVM(&java_vm_) != JNI_OK) throw std::runtime_error("GetJavaVM failed");
        bridge_ = env->NewGlobalRef(bridge);
        if (bridge_ == nullptr) throw std::runtime_error("NewGlobalRef failed");
        jclass bridge_class = env->GetObjectClass(bridge);
        call_sync_ = env->GetMethodID(bridge_class, "callSync", "([B[B)[B");
        call_async_ = env->GetMethodID(bridge_class, "callAsync", "([B[BI)V");
        env->DeleteLocalRef(bridge_class);
        if (call_sync_ == nullptr || call_async_ == nullptr) {
            throw std::runtime_error("NativeBridge method lookup failed");
        }
    }

    ~JsMachine() {
        JNIEnv* env = nullptr;
        if (bridge_ != nullptr && java_vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
            env->DeleteGlobalRef(bridge_);
            bridge_ = nullptr;
        }
    }

    JsMachine(const JsMachine&) = delete;
    JsMachine& operator=(const JsMachine&) = delete;

    /// 执行一段 JS（全局脚本），阻塞到根 Promise settle 或中断。
    std::vector<uint8_t> Run(const std::vector<uint8_t>& code, const std::vector<uint8_t>& filename);

    void Complete(int call_id, bool ok, const std::vector<uint8_t>& payload) {
        {
            std::lock_guard<std::mutex> guard(lock_);
            if (!active_) return;
            queue_.push_back(Completion{call_id, ok, std::string(payload.begin(), payload.end())});
        }
        wake_.notify_all();
    }

    void Interrupt() {
        cancelled_.store(true);
        wake_.notify_all();
    }

    /** 当前脚本主动取消过（用于把「异常」翻译成 stopReason=cancelled）。 */
    bool cancelled() const { return cancelled_.load(); }

private:
    static JsMachine* FromContext(JSContext* ctx) {
        return static_cast<JsMachine*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
    }

    bool CurrentEnv(JNIEnv** env) {
        return java_vm_->GetEnv(reinterpret_cast<void**>(env), JNI_VERSION_1_6) == JNI_OK;
    }

    static jbyteArray ToBytes(JNIEnv* env, const std::string& text) {
        jbyteArray array = env->NewByteArray(static_cast<jsize>(text.size()));
        if (array == nullptr) return nullptr;
        if (!text.empty()) {
            env->SetByteArrayRegion(array, 0, static_cast<jsize>(text.size()),
                                    reinterpret_cast<const jbyte*>(text.data()));
        }
        return array;
    }

    static std::string FromBytes(JNIEnv* env, jbyteArray array) {
        if (array == nullptr) return std::string();
        jsize length = env->GetArrayLength(array);
        std::string out(static_cast<size_t>(length), '\0');
        if (length > 0) {
            env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(&out[0]));
        }
        return out;
    }

    std::string CallHostSync(const std::string& method, const std::string& payload) {
        JNIEnv* env = nullptr;
        if (!CurrentEnv(&env)) return std::string();
        jbyteArray j_method = ToBytes(env, method);
        jbyteArray j_payload = ToBytes(env, payload);
        std::string result;
        if (j_method != nullptr && j_payload != nullptr) {
            auto* raw = static_cast<jbyteArray>(env->CallObjectMethod(bridge_, call_sync_, j_method, j_payload));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                raw = nullptr;
            }
            if (raw != nullptr) {
                result = FromBytes(env, raw);
                env->DeleteLocalRef(raw);
            }
        }
        if (j_method != nullptr) env->DeleteLocalRef(j_method);
        if (j_payload != nullptr) env->DeleteLocalRef(j_payload);
        return result;
    }

    /** 同步 hook：`__dshCallSync(method, payloadJson) -> string`。 */
    static JSValue HostCallSync(JSContext* ctx, JSValueConst, int argc, JSValueConst* argv) {
        JsMachine* machine = FromContext(ctx);
        if (machine == nullptr) return JS_ThrowInternalError(ctx, "JsMachine missing");
        std::string method = argc > 0 ? JsValueToUtf8(ctx, argv[0]) : std::string();
        std::string payload = argc > 1 ? JsValueToUtf8(ctx, argv[1]) : std::string();
        std::string result = machine->CallHostSync(method, payload);
        return JS_NewStringLen(ctx, result.c_str(), result.size());
    }

    /** 异步 hook：`__dshCallAsync(method, payloadJson) -> Promise<string|null>`。 */
    static JSValue HostCallAsync(JSContext* ctx, JSValueConst, int argc, JSValueConst* argv) {
        JsMachine* machine = FromContext(ctx);
        if (machine == nullptr) return JS_ThrowInternalError(ctx, "JsMachine missing");
        std::string method = argc > 0 ? JsValueToUtf8(ctx, argv[0]) : std::string();
        std::string payload = argc > 1 ? JsValueToUtf8(ctx, argv[1]) : std::string();

        JSValue resolve = JS_UNDEFINED;
        JSValue reject = JS_UNDEFINED;
        JSValue resolving[2] = {JS_UNDEFINED, JS_UNDEFINED};
        JSValue promise = JS_NewPromiseCapability(ctx, resolving);
        if (JS_IsException(promise)) return promise;
        resolve = resolving[0];
        reject = resolving[1];

        int call_id = machine->next_call_id_.fetch_add(1);
        machine->pending_[call_id] = PendingCall{resolve, reject};

        JNIEnv* env = nullptr;
        if (!machine->CurrentEnv(&env)) {
            machine->RejectNow(ctx, call_id, "the JS thread is not attached to the JVM", "HOST_UNAVAILABLE");
            return promise;
        }
        jbyteArray j_method = ToBytes(env, method);
        jbyteArray j_payload = ToBytes(env, payload);
        if (j_method != nullptr && j_payload != nullptr) {
            env->CallVoidMethod(machine->bridge_, machine->call_async_, j_method, j_payload, static_cast<jint>(call_id));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                machine->RejectNow(ctx, call_id, "host call failed", "HOST_UNAVAILABLE");
            }
        } else {
            machine->RejectNow(ctx, call_id, "failed to allocate JNI byte arrays", "HOST_UNAVAILABLE");
        }
        if (j_method != nullptr) env->DeleteLocalRef(j_method);
        if (j_payload != nullptr) env->DeleteLocalRef(j_payload);
        return promise;
    }

    /** 直接在本线程把一个 pending promise 结算为失败（不经过队列）。 */
    void RejectNow(JSContext* ctx, int call_id, const std::string& message, const std::string& code) {
        auto it = pending_.find(call_id);
        if (it == pending_.end()) return;
        PendingCall call = it->second;
        pending_.erase(it);
        std::string payload = "{\"message\":";
        AppendJsonString(&payload, message);
        payload += ",\"code\":";
        AppendJsonString(&payload, code);
        payload += ",\"fatal\":true}";
        JSValue error = JS_ParseJSON(ctx, payload.c_str(), payload.size(), "<host-error>");
        if (JS_IsException(error)) {
            JS_FreeValue(ctx, JS_GetException(ctx));
            error = JS_NewString(ctx, message.c_str());
        }
        JSValue ignored = JS_Call(ctx, call.reject, JS_UNDEFINED, 1, &error);
        if (JS_IsException(ignored)) {
            JS_FreeValue(ctx, JS_GetException(ctx));
        } else {
            JS_FreeValue(ctx, ignored);
        }
        JS_FreeValue(ctx, error);
        JS_FreeValue(ctx, call.resolve);
        JS_FreeValue(ctx, call.reject);
    }

    /** 把队列里的 completion 灌回 JS；返回是否处理了至少一条。 */
    bool ApplyCompletions(JSContext* ctx) {
        std::vector<Completion> batch;
        {
            std::lock_guard<std::mutex> guard(lock_);
            if (queue_.empty()) return false;
            batch.swap(queue_);
        }
        for (const Completion& completion : batch) {
            auto it = pending_.find(completion.call_id);
            if (it == pending_.end()) continue;
            PendingCall call = it->second;
            pending_.erase(it);
            JSValue argument;
            if (completion.ok) {
                if (completion.payload.empty()) {
                    argument = JS_UNDEFINED;
                } else {
                    argument = JS_ParseJSON(ctx, completion.payload.c_str(), completion.payload.size(), "<completion>");
                    if (JS_IsException(argument)) {
                        JS_FreeValue(ctx, JS_GetException(ctx));
                        argument = JS_NULL;
                    }
                }
                JSValue ignored = JS_Call(ctx, call.resolve, JS_UNDEFINED, 1, &argument);
                if (JS_IsException(ignored)) {
                    JS_FreeValue(ctx, JS_GetException(ctx));
                } else {
                    JS_FreeValue(ctx, ignored);
                }
            } else {
                argument = JS_ParseJSON(ctx, completion.payload.c_str(), completion.payload.size(), "<completion-error>");
                if (JS_IsException(argument)) {
                    JS_FreeValue(ctx, JS_GetException(ctx));
                    argument = JS_NewString(ctx, "host call failed");
                }
                JSValue ignored = JS_Call(ctx, call.reject, JS_UNDEFINED, 1, &argument);
                if (JS_IsException(ignored)) {
                    JS_FreeValue(ctx, JS_GetException(ctx));
                } else {
                    JS_FreeValue(ctx, ignored);
                }
            }
            JS_FreeValue(ctx, argument);
            JS_FreeValue(ctx, call.resolve);
            JS_FreeValue(ctx, call.reject);
        }
        return !batch.empty();
    }

    static int InterruptHandler(JSRuntime*, void* opaque) {
        auto* machine = static_cast<JsMachine*>(opaque);
        if (machine == nullptr) return 0;
        return machine->cancelled_.load() ? 1 : 0;
    }

    /** 把捕获到的返回值/异常翻译成 envelope JSON（UTF-8）。 */
    std::string SettleEnvelope(JSContext* ctx) {
        std::string envelope;
        if (ok_) {
            envelope = "{\"status\":\"completed\",\"value\":";
            if (JS_IsUndefined(value_)) {
                envelope += "null";
            } else {
                JSValue json = JS_JSONStringify(ctx, value_, JS_UNDEFINED, JS_UNDEFINED);
                if (JS_IsException(json)) {
                    JS_FreeValue(ctx, JS_GetException(ctx));
                    return ErrorEnvelope("the workflow's return value is not plain JSON data — JSON.stringify failed");
                }
                if (JS_IsUndefined(json)) {
                    JS_FreeValue(ctx, json);
                    return ErrorEnvelope(
                        "the workflow's return value is not plain JSON data — return only JSON-serializable objects/arrays/scalars");
                }
                std::string rendered = JsValueToUtf8(ctx, json);
                JS_FreeValue(ctx, json);
                envelope += rendered;
            }
            envelope += "}";
        } else {
            envelope = ErrorEnvelope(RenderThrown(ctx, value_));
        }
        return envelope;
    }

    /** JS 侧把最终值交回来（正常完成）。 */
    static JSValue HostDone(JSContext* ctx, JSValueConst, int argc, JSValueConst* argv) {
        JsMachine* machine = FromContext(ctx);
        if (machine == nullptr || machine->done_) return JS_UNDEFINED;
        machine->done_ = true;
        machine->ok_ = true;
        machine->value_ = argc > 0 ? JS_DupValue(ctx, argv[0]) : JS_UNDEFINED;
        return JS_UNDEFINED;
    }

    /** JS 侧把异常交回来（脚本 reject / throw）。 */
    static JSValue HostFail(JSContext* ctx, JSValueConst, int argc, JSValueConst* argv) {
        JsMachine* machine = FromContext(ctx);
        if (machine == nullptr || machine->done_) return JS_UNDEFINED;
        machine->done_ = true;
        machine->ok_ = false;
        machine->value_ = argc > 0 ? JS_DupValue(ctx, argv[0]) : JS_UNDEFINED;
        return JS_UNDEFINED;
    }

    static std::string ErrorEnvelope(const std::string& error) {
        std::string envelope = "{\"status\":\"error\",\"error\":";
        AppendJsonString(&envelope, error);
        envelope += "}";
        return envelope;
    }

    JavaVM* java_vm_ = nullptr;
    jobject bridge_ = nullptr;
    jmethodID call_sync_ = nullptr;
    jmethodID call_async_ = nullptr;

    std::mutex lock_;
    std::condition_variable wake_;
    std::vector<Completion> queue_;
    std::atomic_bool cancelled_{false};
    std::atomic_bool active_{false};
    std::atomic_int next_call_id_{0};
    std::unordered_map<int, PendingCall> pending_;  // 只在 JS 线程上访问

    // 根结果（只在 JS 线程上读写）：脚本以 done/fail 回调交回，避免依赖 PromiseState。
    bool done_ = false;
    bool ok_ = true;
    JSValue value_ = JS_UNDEFINED;
};

std::vector<uint8_t> JsMachine::Run(const std::vector<uint8_t>& code, const std::vector<uint8_t>& filename) {
    const std::string name(filename.begin(), filename.end());
    JSRuntime* runtime = JS_NewRuntime();
    if (runtime == nullptr) {
        std::string envelope = ErrorEnvelope("JS_NewRuntime failed");
        return std::vector<uint8_t>(envelope.begin(), envelope.end());
    }
    JS_SetRuntimeOpaque(runtime, this);
    JS_SetMemoryLimit(runtime, 256ull * 1024 * 1024);
    JS_SetMaxStackSize(runtime, 4ull * 1024 * 1024);
    JS_SetInterruptHandler(runtime, &JsMachine::InterruptHandler, this);

    JSContext* ctx = JS_NewContext(runtime);
    if (ctx == nullptr) {
        JS_FreeRuntime(runtime);
        std::string envelope = ErrorEnvelope("JS_NewContext failed");
        return std::vector<uint8_t>(envelope.begin(), envelope.end());
    }

    {
        JSValue global = JS_GetGlobalObject(ctx);
        JS_SetPropertyStr(ctx, global, "__dshCallSync",
                          JS_NewCFunction(ctx, &JsMachine::HostCallSync, "__dshCallSync", 2));
        JS_SetPropertyStr(ctx, global, "__dshCallAsync",
                          JS_NewCFunction(ctx, &JsMachine::HostCallAsync, "__dshCallAsync", 2));
        JS_SetPropertyStr(ctx, global, "__dshDone",
                          JS_NewCFunction(ctx, &JsMachine::HostDone, "__dshDone", 1));
        JS_SetPropertyStr(ctx, global, "__dshFail",
                          JS_NewCFunction(ctx, &JsMachine::HostFail, "__dshFail", 1));
        JS_FreeValue(ctx, global);
    }

    // 统一包装：脚本可以是普通表达式或返回 Promise 的表达式；两路最终都汇到 done/fail。
    // 之所以不读 JS_PromiseState/JS_PromiseResult：QuickJS 的 promise 状态读取对
    // 非 Promise 值/特定返回形态不可靠（真机实测过同一脚本偶发空错误栈），回调最稳。
    std::string wrapped =
        "(function(){ try { const __r = (function(){\n";
    wrapped.append(reinterpret_cast<const char*>(code.data()), code.size());
    wrapped += "\n})(); if (__r && typeof __r.then === \"function\") { __r.then(__dshDone, __dshFail); } "
               "else { __dshDone(__r); } } catch (__e) { __dshFail(__e); } })();";

    active_.store(true);
    JSValue evaluated = JS_Eval(ctx, wrapped.c_str(), wrapped.size(), name.c_str(), JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(evaluated)) {
        JSValue exception = JS_GetException(ctx);
        std::string rendered = RenderThrown(ctx, exception);
        JS_FreeValue(ctx, exception);
        active_.store(false);
        JS_FreeContext(ctx);
        JS_FreeRuntime(runtime);
        std::string envelope = ErrorEnvelope(rendered);
        return std::vector<uint8_t>(envelope.begin(), envelope.end());
    }
    JS_FreeValue(ctx, evaluated);

    std::string envelope = "{\"status\":\"interrupted\"}";
    for (;;) {
        if (done_) {
            envelope = SettleEnvelope(ctx);
            break;
        }
        JSContext* job_context = nullptr;
        while (JS_ExecutePendingJob(runtime, &job_context) > 0) {
        }
        if (ApplyCompletions(ctx)) continue;
        if (cancelled_.load()) {
            envelope = "{\"status\":\"interrupted\"}";
            break;
        }
        if (done_) {
            envelope = SettleEnvelope(ctx);
            break;
        }
        std::unique_lock<std::mutex> lock(lock_);
        wake_.wait_for(lock, std::chrono::milliseconds(20), [this] {
            return !queue_.empty() || cancelled_.load();
        });
    }

    active_.store(false);
    if (!JS_IsUndefined(value_)) JS_FreeValue(ctx, value_);
    value_ = JS_UNDEFINED;
    JS_FreeContext(ctx);
    JS_FreeRuntime(runtime);  // 未结算的 pending JSValue 随 runtime 释放
    pending_.clear();
    return std::vector<uint8_t>(envelope.begin(), envelope.end());
}

JsMachine* FromHandle(jlong handle) { return reinterpret_cast<JsMachine*>(handle); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_xin_dshaa_core_js_NativeQuickJs_create(JNIEnv* env, jobject, jobject bridge) {
    if (bridge == nullptr) return 0;
    try {
        return reinterpret_cast<jlong>(new JsMachine(env, bridge));
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_xin_dshaa_core_js_NativeQuickJs_destroy(JNIEnv*, jobject, jlong handle) {
    delete FromHandle(handle);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_xin_dshaa_core_js_NativeQuickJs_run(JNIEnv* env, jobject, jlong handle, jbyteArray code, jbyteArray filename) {
    JsMachine* machine = FromHandle(handle);
    if (machine == nullptr || code == nullptr) return nullptr;
    std::vector<uint8_t> code_bytes;
    std::vector<uint8_t> filename_bytes;
    {
        jsize length = env->GetArrayLength(code);
        code_bytes.resize(static_cast<size_t>(length));
        if (length > 0) env->GetByteArrayRegion(code, 0, length, reinterpret_cast<jbyte*>(code_bytes.data()));
    }
    if (filename != nullptr) {
        jsize length = env->GetArrayLength(filename);
        filename_bytes.resize(static_cast<size_t>(length));
        if (length > 0) env->GetByteArrayRegion(filename, 0, length, reinterpret_cast<jbyte*>(filename_bytes.data()));
    }
    std::vector<uint8_t> result = machine->Run(code_bytes, filename_bytes);
    jbyteArray array = env->NewByteArray(static_cast<jsize>(result.size()));
    if (array == nullptr) return nullptr;
    if (!result.empty()) {
        env->SetByteArrayRegion(array, 0, static_cast<jsize>(result.size()),
                                reinterpret_cast<const jbyte*>(result.data()));
    }
    return array;
}

extern "C" JNIEXPORT void JNICALL
Java_xin_dshaa_core_js_NativeQuickJs_complete(JNIEnv* env, jobject, jlong handle, jint call_id, jboolean ok,
                                              jbyteArray payload) {
    JsMachine* machine = FromHandle(handle);
    if (machine == nullptr) return;
    std::vector<uint8_t> bytes;
    if (payload != nullptr) {
        jsize length = env->GetArrayLength(payload);
        bytes.resize(static_cast<size_t>(length));
        if (length > 0) env->GetByteArrayRegion(payload, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    }
    machine->Complete(static_cast<int>(call_id), ok == JNI_TRUE, bytes);
}

extern "C" JNIEXPORT void JNICALL
Java_xin_dshaa_core_js_NativeQuickJs_interrupt(JNIEnv*, jobject, jlong handle) {
    JsMachine* machine = FromHandle(handle);
    if (machine != nullptr) machine->Interrupt();
}
