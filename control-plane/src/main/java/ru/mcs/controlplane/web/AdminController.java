package ru.mcs.controlplane.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mcs.controlplane.domain.Tier;
import ru.mcs.controlplane.dto.CodeView;
import ru.mcs.controlplane.dto.UserView;
import ru.mcs.controlplane.service.AccessCodeService;
import ru.mcs.controlplane.service.UserService;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AccessCodeService accessCodeService;
    private final UserService userService;

    @PostMapping("/codes")
    public GenerateResponse generate(@RequestBody GenerateRequest request) {
        var codes = accessCodeService.generate(request.count(), request.tier(), request.expiresAt());
        return new GenerateResponse(codes);
    }

    @GetMapping("/codes")
    public List<CodeView> codes() {
        return accessCodeService.listCodes().stream().map(CodeView::from).toList();
    }

    @GetMapping("/users")
    public List<UserView> users() {
        return userService.list().stream().map(UserView::from).toList();
    }

    @PostMapping("/users/{id}/tier")
    public UserView changeTier(@PathVariable UUID id, @RequestBody ChangeTierRequest request) {
        var user = userService.changeTier(id, request.tier());
        return UserView.from(user);
    }

    public record GenerateRequest(int count, Tier tier, Instant expiresAt) {
    }

    public record GenerateResponse(List<String> codes) {
    }

    public record ChangeTierRequest(Tier tier) {
    }
}
