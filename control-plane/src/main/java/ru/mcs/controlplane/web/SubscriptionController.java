package ru.mcs.controlplane.web;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mcs.controlplane.service.AccessCodeService;
import ru.mcs.controlplane.service.UserService;

@RestController
@RequestMapping("/api/subscriptions")
@RequiredArgsConstructor
public class SubscriptionController {

    private final AccessCodeService accessCodeService;
    private final UserService userService;

    @PostMapping("/activate")
    public ActivateResponse activate(@RequestBody ActivateRequest request, Authentication authentication) {
        var user = userService.getCurrent(authentication);
        var tier = accessCodeService.activate(user.getId(), request.code());
        return new ActivateResponse(tier.name());
    }

    public record ActivateRequest(String code) {
    }

    public record ActivateResponse(String tier) {
    }
}
