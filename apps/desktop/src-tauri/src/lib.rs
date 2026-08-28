/// 壳零逻辑(D-7 / ADR-0006):默认 Builder + generate_context,不注册任何 command。
/// 加载的 web 产物见 tauri.conf.json(frontendDist → apps/web 构建产物);
/// 后端地址与 token 由 web 层"连接设置"运行时配置,壳不感知。
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
