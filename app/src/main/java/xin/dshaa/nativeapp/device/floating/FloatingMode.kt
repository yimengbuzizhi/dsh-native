package xin.dshaa.nativeapp.device.floating

/**
 * Operit `FloatingMode` 的逐项移植（docs/76 §六）。
 *
 * 六个模式是并列的，不是「球/窗」两级；每个模式在 FloatingWindowManager 里都有自己的
 * LayoutParams、进出路径和 previousMode 回退。
 */
enum class FloatingMode {
    WINDOW,
    BALL,
    VOICE_BALL,
    FULLSCREEN,
    RESULT_DISPLAY,
    SCREEN_OCR,
}
