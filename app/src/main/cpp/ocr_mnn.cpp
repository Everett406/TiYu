// v2.16.0 拍照搜题 OCR 引擎胶水层（MNN 3.6.1）。
// 职责最小化：加载 .mnn 模型 → 按调用方给定的 NCHW 形状 resize → 推理 → 返回输出数据与形状。
// 所有预处理（缩放/归一化）/后处理（DB 阈值/连通域/行合并/CTC 解码）在 Kotlin 层完成，
// 与沙箱验证脚本 scripts/ocr_validate.py 逐行同构。
#include <jni.h>
#include <cstring>
#include <mutex>
#include <vector>

#include "MNN/Interpreter.hpp"
#include "MNN/Tensor.hpp"
#include "MNN/MNNDefine.h"

namespace {

// 一个模型会话：det / rec 各持有自己的 Interpreter + Session。
struct OcrEngine {
    std::shared_ptr<MNN::Interpreter> net;
    MNN::Session *session = nullptr;
    std::mutex mu;   // 单会话串行推理（Kotlin 侧不会并发调用，双保险）
};

} // namespace

extern "C" {

// 创建引擎：modelPath 指向 filesDir 下的 .mnn 文件。失败返回 0。
JNIEXPORT jlong JNICALL
Java_com_drone_quiz_ocr_OcrNative_nativeCreate(JNIEnv *env, jobject, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;
    MNN::Interpreter *raw = MNN::Interpreter::createFromFile(path);
    env->ReleaseStringUTFChars(modelPath, path);
    if (raw == nullptr) return 0;

    auto *engine = new OcrEngine();
    engine->net.reset(raw);
    MNN::ScheduleConfig config;
    config.numThread = 4;
    config.type = MNN_FORWARD_CPU;
    MNN::Session *s = engine->net->createSession(config);
    if (s == nullptr) {
        delete engine;
        return 0;
    }
    engine->session = s;
    return reinterpret_cast<jlong>(engine);
}

// 推理：input 长度 = c*h*w（NCHW float32），shape = [1,c,h,w]。
// 返回输出 float 数组；shapeOut（长度 ≥4）回填输出形状。失败返回 NULL。
JNIEXPORT jfloatArray JNICALL
Java_com_drone_quiz_ocr_OcrNative_nativeRun(JNIEnv *env, jobject, jlong ptr,
                                            jfloatArray input, jintArray shape,
                                            jintArray shapeOut) {
    auto *engine = reinterpret_cast<OcrEngine *>(ptr);
    if (engine == nullptr || engine->session == nullptr) return nullptr;

    std::lock_guard<std::mutex> lock(engine->mu);

    if (env->GetArrayLength(shape) != 4) return nullptr;
    jint dimsArr[4];
    env->GetIntArrayRegion(shape, 0, 4, dimsArr);
    std::vector<int> dims(dimsArr, dimsArr + 4);

    jsize inLen = env->GetArrayLength(input);
    jfloat *inData = env->GetFloatArrayElements(input, nullptr);
    if (inData == nullptr) return nullptr;

    jfloatArray result = nullptr;
    do {
        MNN::Tensor *deviceInput = engine->net->getSessionInput(engine->session, nullptr);
        if (deviceInput == nullptr) break;
        engine->net->resizeTensor(deviceInput, dims);
        engine->net->resizeSession(engine->session);

        // 主机张量 → 设备输入
        std::shared_ptr<MNN::Tensor> hostInput(
                MNN::Tensor::create<float>(dims, nullptr, MNN::Tensor::CAFFE));
        if (hostInput == nullptr) break;
        if (static_cast<jsize>(hostInput->elementSize()) != inLen) break;
        std::memcpy(hostInput->host<float>(), inData,
                    static_cast<size_t>(inLen) * sizeof(float));
        if (!deviceInput->copyFromHostTensor(hostInput.get())) break;

        if (!engine->net->runSession(engine->session)) break;

        MNN::Tensor *deviceOutput = engine->net->getSessionOutput(engine->session, nullptr);
        if (deviceOutput == nullptr) break;
        std::shared_ptr<MNN::Tensor> hostOutput(
                MNN::Tensor::createHostTensorFromDevice(deviceOutput, false));
        if (hostOutput == nullptr) break;
        if (!deviceOutput->copyToHostTensor(hostOutput.get())) break;

        const std::vector<int> &outDims = hostOutput->shape();
        if (outDims.size() < 4) break;

        // 回填输出形状（调用方按 shape 解释时间/类别维度）
        std::vector<jint> js(outDims.begin(), outDims.end());
        if (env->GetArrayLength(shapeOut) < static_cast<jsize>(js.size())) break;
        env->SetIntArrayRegion(shapeOut, 0, static_cast<jsize>(js.size()), js.data());

        size_t n = hostOutput->elementSize();
        result = env->NewFloatArray(static_cast<jsize>(n));
        if (result == nullptr) break;
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(n),
                                 hostOutput->host<float>());
    } while (false);

    env->ReleaseFloatArrayElements(input, inData, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL
Java_com_drone_quiz_ocr_OcrNative_nativeClose(JNIEnv *env, jobject, jlong ptr) {
    auto *engine = reinterpret_cast<OcrEngine *>(ptr);
    if (engine == nullptr) return;
    std::lock_guard<std::mutex> lock(engine->mu);
    if (engine->session != nullptr) {
        engine->net->releaseSession(engine->session);
        engine->session = nullptr;
    }
    engine->net.reset();
    delete engine;
}

} // extern "C"
