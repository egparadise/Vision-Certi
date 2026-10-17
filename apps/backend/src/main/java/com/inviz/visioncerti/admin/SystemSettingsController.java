package com.inviz.visioncerti.admin;

import com.inviz.visioncerti.admin.SystemSettings.ResetSettingRequest;
import com.inviz.visioncerti.admin.SystemSettings.SettingView;
import com.inviz.visioncerti.admin.SystemSettings.SettingsResponse;
import com.inviz.visioncerti.admin.SystemSettings.UpdateSettingRequest;
import com.inviz.visioncerti.security.RequestActor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-managed operational policy values (ADMIN; changes only by the configured operating tenants). */
@RestController
@RequestMapping("/v1/admin/settings")
public class SystemSettingsController {

    private final SystemSettings settings;

    public SystemSettingsController(SystemSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public ResponseEntity<SettingsResponse> list(RequestActor actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.list(actor));
    }

    @PutMapping("/{key}")
    public SettingView update(RequestActor actor, @PathVariable String key, @RequestBody UpdateSettingRequest body) {
        return settings.update(actor, key, body);
    }

    @PostMapping("/{key}/reset")
    public SettingView reset(RequestActor actor, @PathVariable String key, @RequestBody ResetSettingRequest body) {
        return settings.reset(actor, key, body);
    }
}
