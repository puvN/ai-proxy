package ru.mcs.controlplane.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mcs.controlplane.dto.UsageResponse;
import ru.mcs.controlplane.service.UsageService;
import ru.mcs.controlplane.service.UserService;

@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;
    private final UserService userService;

    @GetMapping
    public UsageResponse usage(Authentication authentication) {
        var user = userService.getCurrent(authentication);
        return usageService.usage(user.getId(), user.getTier());
    }
}
