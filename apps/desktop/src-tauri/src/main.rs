// 与 WebView2 无关的窗口属性:release 构建不弹控制台(默认模板口径)
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    reader_desktop_lib::run()
}
