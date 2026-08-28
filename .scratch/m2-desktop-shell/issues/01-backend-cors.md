# 01: 后端 CORS 放行 + Seam A 集成测试

**What to build:** 让任意 origin 的跨域 API 调用(桌面壳 WebView2、以及一切非同源部署的 web 产物)能正常使用后端:全局 CORS 配置——放行任意 origin、不放行 credentials(鉴权走 Authorization 头而非 cookie)、放行 Authorization / Content-Type 头与 GET/POST/PUT/DELETE/OPTIONS 方法;静态 token 鉴权与 CORS 正交:跨域不带 token 一律 401 不变,鉴权过滤器对 OPTIONS preflight 放行(浏览器 preflight 不携带凭据,不得被 401 误杀)。完成即 curl 可演示:带 Origin 的 OPTIONS 握手成功、带 Origin + token 的业务请求正常、跨域无 token 仍 401。全部行为以 Testcontainers 集成测试落档(Seam A 扩展),既有测试零回归。

**Blocked by:** None(可立即开始)

**Status:** done

- [x] OPTIONS preflight(带 Origin、Access-Control-Request-Method/Headers)→ 2xx 且 Access-Control-Allow-Origin / -Allow-Headers / -Allow-Methods 正确
- [x] preflight 不带 token 也不被 401 拦截(preflight 不携带凭据是浏览器语义)
- [x] 带 Origin + token 的实际请求:响应携带 Access-Control-Allow-Origin,业务行为与同源请求一致(含错误文案)
- [x] 跨域不带 token → 401 不变(CORS 放行 ≠ 鉴权放行)
- [x] 不放行 credentials(无 Access-Control-Allow-Credentials)
- [x] 既有全部集成测试零回归,mvn test 绿
