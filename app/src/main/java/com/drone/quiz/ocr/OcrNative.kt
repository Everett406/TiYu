package com.drone.quiz.ocr

/**
 * v2.16.0 MNN 推理 JNI 封装。native 层职责最小化（加载/resize/推理/释放），
 * 预处理与后处理全部在 Kotlin 层（[PaddleOcr]）完成。
 */
internal object OcrNative {
    init {
        System.loadLibrary("droneocr")
    }

    /** 创建推理会话；失败返回 0。 */
    external fun nativeCreate(modelPath: String): Long

    /**
     * 推理一次。
     * @param input NCHW float 数组（长度 = c*h*w）
     * @param shape 输入形状 [1,c,h,w]
     * @param shapeOut 输出形状回填（调用方分配长度 ≥4 的数组）
     * @return 输出 float 数组；失败返回 null
     */
    external fun nativeRun(ptr: Long, input: FloatArray, shape: IntArray, shapeOut: IntArray): FloatArray?

    external fun nativeClose(ptr: Long)
}
