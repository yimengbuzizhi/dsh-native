package xin.dshaa.nativeapp.ui.shell.native

import xin.dshaa.ui.shellapi.NativeShell
import xin.dshaa.ui.shellapi.NativeShellProvider

/**
 * M85-C3-3c：`ui:shell-api` 通过 `META-INF/services` 发现这个 provider，
 * 从而让 app 只 import shell-api 的 `NativeShells`、不反向 import 具体壳/页面。
 */
class NativeShellService : NativeShellProvider {
    override fun create(): NativeShell = BuiltinNativeShell
}
