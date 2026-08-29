package com.smy101.reader.settings;

import com.smy101.reader.settings.dto.ModelSettingsDto;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型设置 API(M3-01,FR-401/404/405):单套配置读写 + 测试连接代理。
 * 均受既有 token 拦截(401 防线不变);错误一律 {"error": 可读文案}。
 */
@RestController
@RequiredArgsConstructor
public class ModelSettingsController {

    private final ModelSettingsService service;

    /** 读取单套配置;从未保存过返回全空字段(端上空表单)。 */
    @GetMapping("/api/settings/model")
    public ModelSettingsDto get() {
        return service.get();
    }

    /** 保存(整行覆盖);base URL 与 chat 模型必填。 */
    @PutMapping("/api/settings/model")
    public ModelSettingsDto save(@RequestBody ModelSettingsDto.SaveRequest request) {
        return service.save(request);
    }

    /** 测试连接(FR-405):chat 与 embedding 双探针;body 可省(测已保存配置)。 */
    @PostMapping("/api/settings/model/test")
    public ModelSettingsDto.TestConnectionResult test(
            @RequestBody(required = false) ModelSettingsDto.SaveRequest request) {
        return service.test(request);
    }
}
